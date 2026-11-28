package com.bilpark.backend.auth;

import com.bilpark.backend.common.ClientIp;
import com.bilpark.backend.common.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Clock;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * In-memory brute-force protection for {@code POST /api/auth/login}.
 * <ul>
 *   <li><b>Lock per username AND client IP:</b> after {@code max-failures} failed attempts in a row from one
 *       address (no gap longer than {@code failure-window-seconds}) that username is locked <i>for that address</i>
 *       for the next length in {@code lock-seconds} (1, 5, 15 min by default; the last one repeats). Someone who
 *       only knows a username cannot lock its owner out from another address. A successful login forgets the
 *       pair. Unknown usernames are counted exactly like existing ones, so the answers never tell them apart.</li>
 *   <li><b>Per username, all addresses:</b> {@code username-alert-failures} failures in one
 *       {@code failure-window-seconds} window are reported once (audit row) but lock nothing.</li>
 *   <li><b>Per client IP:</b> at most {@code per-ip} attempts per {@code ip-window-seconds}. Many officers share
 *       one carrier (CGNAT) address, so this is generous and a successful login gives its slot back: what fills the
 *       window is failed (and still running) attempts, i.e. trying many usernames.</li>
 * </ul>
 * IPv6 clients are keyed by their /64 network (one subscriber usually holds a whole /64). An attempt reserves its
 * slots in {@link #begin} before the password is checked, so parallel requests cannot slip past a limit;
 * {@link #failed}, {@link #succeeded} or {@link #abandon} settles it. An attempt that never reached the password
 * check (database unavailable) is abandoned: it counts as nothing.
 * <p>
 * Memory is bounded by {@code max-tracked-keys} per map. When a map is full, expired entries are dropped, then the
 * least recently used ones. One app instance (Render Free); a restart clears everything. A lock ends when its time
 * is up, or when someone resets that user's password ({@link #clear}). Settings: {@code bilpark.rate-limit.login.*}.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    static final String LOCKED_MESSAGE =
            "Çok sayıda hatalı deneme yapıldı. Güvenliğiniz için %d dakika sonra tekrar deneyebilirsiniz.";
    static final String IP_MESSAGE =
            "Bu bağlantıdan kısa sürede çok sayıda giriş denemesi yapıldı. Lütfen %d dakika sonra tekrar deneyin.";
    static final String BUSY_MESSAGE = "Önceki giriş denemeniz sürüyor. Lütfen birkaç saniye sonra tekrar deneyin.";
    static final int MAX_KEY_LENGTH = 128;

    /** Why an attempt was refused. */
    public enum Reason { LOCKED, IP_LIMIT, BUSY }

    /**
     * A refused attempt (429). {@code report} is true once per lock / per IP window: the caller writes one audit
     * row then, not one per refused request.
     */
    public record Refusal(Reason reason, String code, long retryAfterSeconds, String message, boolean report,
                          int failures, long lockSeconds) {}

    /**
     * Outcome of a wrong password. {@code lock}: this failure locked the pair (429). {@code remainingAttempts}:
     * failures left before the lock, -1 when not tracked. {@code alert}: the username crossed the
     * all-addresses threshold just now ({@code usernameFailures} in the window), write one audit row.
     */
    public record Failure(Refusal lock, int remainingAttempts, boolean alert, int usernameFailures) {
        static final Failure NONE = new Failure(null, -1, false, 0);
    }

    /** One login attempt between {@link #begin} and its outcome. */
    public static final class Attempt {
        private final Refusal refusal;
        private final String userKey;
        private final String pairKey;      // null: pair not tracked (limiter disabled)
        private final String ipKey;        // null: IP not tracked
        private final long ipWindowStart;
        private boolean settled;

        private Attempt(Refusal refusal, String userKey, String pairKey, String ipKey, long ipWindowStart) {
            this.refusal = refusal;
            this.userKey = userKey;
            this.pairKey = pairKey;
            this.ipKey = ipKey;
            this.ipWindowStart = ipWindowStart;
            this.settled = refusal != null || pairKey == null && ipKey == null;
        }

        /** Non-null: refused before the password check, nothing was reserved. */
        public Refusal refusal() { return refusal; }
    }

    private static final class Pair {
        int failures;      // failed attempts since the last lock / success
        int pending;       // attempts whose password check is still running
        long lastFailure;
        long lockedUntil;  // epoch millis; locked while now < lockedUntil
        int locks;         // locks so far: picks the next lock length
    }

    private static final class Window {
        final long start;
        int count;
        boolean reported;

        Window(long start) { this.start = start; }
    }

    private final boolean enabled;
    private final int maxFailures;
    private final long failureWindowMillis;
    private final long[] lockMillis;
    private final int usernameAlertFailures;
    private final int perIp;
    private final long ipWindowMillis;
    private final int maxTrackedKeys;
    private final ClientIp clientIp;
    private final Clock clock;

    // access order: iteration starts at the least recently used entry (evicted first when full); guarded by this
    private final LinkedHashMap<String, Pair> pairs = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, Window> usernames = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, Window> ips = new LinkedHashMap<>(16, 0.75f, true);
    private boolean warnedFull; // guarded by this

    @Autowired
    public LoginRateLimiter(
            @Value("${bilpark.rate-limit.login.enabled:true}") boolean enabled,
            @Value("${bilpark.rate-limit.login.max-failures:6}") int maxFailures,
            @Value("${bilpark.rate-limit.login.failure-window-seconds:900}") int failureWindowSeconds,
            @Value("${bilpark.rate-limit.login.lock-seconds:60,300,900}") String lockSeconds,
            @Value("${bilpark.rate-limit.login.username-alert-failures:100}") int usernameAlertFailures,
            @Value("${bilpark.rate-limit.login.per-ip:30}") int perIp,
            @Value("${bilpark.rate-limit.login.ip-window-seconds:60}") int ipWindowSeconds,
            @Value("${bilpark.rate-limit.login.max-tracked-keys:10000}") int maxTrackedKeys,
            ClientIp clientIp,
            Clock clock) {
        this.enabled = enabled;
        this.maxFailures = Math.max(1, maxFailures);
        this.failureWindowMillis = Math.max(1, failureWindowSeconds) * 1000L;
        this.lockMillis = parseLocks(lockSeconds);
        this.usernameAlertFailures = Math.max(1, usernameAlertFailures);
        this.perIp = Math.max(1, perIp);
        this.ipWindowMillis = Math.max(1, ipWindowSeconds) * 1000L;
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
        this.clientIp = clientIp;
        this.clock = clock;
    }

    static long[] parseLocks(String s) {
        long[] v = s == null ? new long[0] : Arrays.stream(s.split(","))
                .map(String::trim).filter(x -> !x.isEmpty())
                .mapToLong(Long::parseLong).map(x -> Math.max(1, x) * 1000L).toArray();
        return v.length > 0 ? v : new long[]{60_000L};
    }

    /** Starts an attempt for the request's client IP; check {@link Attempt#refusal()} first. */
    public Attempt begin(HttpServletRequest request, String username) {
        return begin(clientIp.of(request), username);
    }

    synchronized Attempt begin(String ip, String username) {
        if (!enabled) return new Attempt(null, null, null, null, 0);
        long now = clock.millis();
        String userKey = key(username);
        String ipKey = ipKey(ip);
        String pairKey = userKey + "|" + ipKey; // an IP key never contains '|'

        Pair p = pairs.get(pairKey);
        if (p != null && pairIdle(p, now)) {
            pairs.remove(pairKey);
            p = null;
        }
        if (p != null && p.failures > 0 && now - p.lastFailure >= failureWindowMillis) p.failures = 0;
        if (p != null && now < p.lockedUntil) return refused(lockRefusal(p, now));
        // failures plus attempts still being checked: parallel requests cannot pass the limit together
        if (p != null && p.failures + p.pending >= maxFailures) {
            return refused(new Refusal(Reason.BUSY, ErrorCodes.RATE_LIMITED, 1, BUSY_MESSAGE, false, p.failures, 0));
        }

        Window w = current(ips, ipKey, now, ipWindowMillis);
        if (w != null && w.count >= perIp) {
            boolean report = !w.reported;
            w.reported = true;
            long ms = w.start + ipWindowMillis - now;
            return refused(new Refusal(Reason.IP_LIMIT, ErrorCodes.RATE_LIMITED, seconds(ms),
                    String.format(Locale.ROOT, IP_MESSAGE, minutes(ms)), report, 0, 0));
        }

        // reserve
        if (w == null) {
            makeRoom(ips, x -> now - x.start >= ipWindowMillis);
            w = new Window(now);
            ips.put(ipKey, w);
        }
        w.count++;
        if (p == null) {
            makeRoom(pairs, x -> pairIdle(x, now));
            p = new Pair();
            pairs.put(pairKey, p);
        }
        p.pending++;
        return new Attempt(null, userKey, pairKey, ipKey, w.start);
    }

    /** Wrong username or password: see {@link Failure}. */
    public synchronized Failure failed(Attempt a) {
        if (a == null || a.settled) return Failure.NONE;
        a.settled = true;
        long now = clock.millis();

        // all addresses together: report only, never lock
        Window total = current(usernames, a.userKey, now, failureWindowMillis);
        if (total == null) {
            makeRoom(usernames, x -> now - x.start >= failureWindowMillis);
            total = new Window(now);
            usernames.put(a.userKey, total);
        }
        total.count++;
        boolean alert = total.count >= usernameAlertFailures && !total.reported;
        if (alert) total.reported = true;

        Pair p = a.pairKey == null ? null : pairs.get(a.pairKey);
        if (p == null) return new Failure(null, -1, alert, total.count); // evicted meanwhile
        if (p.pending > 0) p.pending--;
        if (now < p.lockedUntil) return new Failure(lockRefusal(p, now), 0, alert, total.count);
        if (p.failures > 0 && now - p.lastFailure >= failureWindowMillis) p.failures = 0;
        p.failures++;
        p.lastFailure = now;
        if (p.failures < maxFailures) return new Failure(null, maxFailures - p.failures, alert, total.count);

        int failures = p.failures;
        long lock = lockMillis[Math.min(p.locks, lockMillis.length - 1)];
        p.locks++;
        p.failures = 0;
        p.lockedUntil = now + lock;
        Refusal r = new Refusal(Reason.LOCKED, ErrorCodes.LOGIN_LOCKED, lock / 1000,
                String.format(Locale.ROOT, LOCKED_MESSAGE, minutes(lock)), true, failures, lock / 1000);
        return new Failure(r, 0, alert, total.count);
    }

    /** Correct password: the username/IP pair is forgotten, the IP slot is given back. */
    public synchronized void succeeded(Attempt a) {
        if (a == null || a.settled) return;
        a.settled = true;
        if (a.pairKey != null) pairs.remove(a.pairKey);
        releaseIp(a);
    }

    /** The password was never checked (database unavailable, unexpected error): nothing is counted. No-op once settled. */
    public synchronized void abandon(Attempt a) {
        if (a == null || a.settled) return;
        a.settled = true;
        if (a.pairKey != null) {
            Pair p = pairs.get(a.pairKey);
            if (p != null && p.pending > 0) p.pending--;
        }
        releaseIp(a);
    }

    /** Lifts every lock of a username, from every address (its password was reset by an admin / developer). */
    public synchronized void clear(String username) {
        String userKey = key(username);
        for (var e : pairs.entrySet()) {
            String k = e.getKey();
            if (k.substring(0, k.lastIndexOf('|')).equals(userKey)) {
                Pair p = e.getValue();
                p.failures = 0;
                p.lockedUntil = 0;
                p.locks = 0;
            }
        }
    }

    /** Tracked pairs / usernames / IPs (tests). */
    synchronized int trackedPairs() { return pairs.size(); }

    synchronized int trackedUsernames() { return usernames.size(); }

    synchronized int trackedIps() { return ips.size(); }

    private void releaseIp(Attempt a) {
        if (a.ipKey == null) return;
        Window w = ips.get(a.ipKey);
        // only the window the slot was taken from: a new window starts with its own count
        if (w != null && w.start == a.ipWindowStart && w.count > 0) w.count--;
    }

    /** The map's window for {@code key}, null when there is none or it has expired (then it is removed). */
    private static Window current(LinkedHashMap<String, Window> map, String key, long now, long windowMillis) {
        Window w = map.get(key);
        if (w != null && now - w.start >= windowMillis) {
            map.remove(key);
            return null;
        }
        return w;
    }

    private boolean pairIdle(Pair p, long now) {
        return p.pending == 0 && now >= p.lockedUntil
                && now - Math.max(p.lastFailure, p.lockedUntil) >= failureWindowMillis;
    }

    private Refusal lockRefusal(Pair p, long now) {
        long ms = p.lockedUntil - now;
        return new Refusal(Reason.LOCKED, ErrorCodes.LOGIN_LOCKED, seconds(ms),
                String.format(Locale.ROOT, LOCKED_MESSAGE, minutes(ms)), false, 0, 0);
    }

    private static long seconds(long ms) { return Math.max(1, (ms + 999) / 1000); }

    private static long minutes(long ms) { return Math.max(1, (ms + 59_999) / 60_000); }

    private static Attempt refused(Refusal r) {
        return new Attempt(r, null, null, null, 0);
    }

    /** Room for one more key: expired entries go first, then the least recently used ones. */
    private <V> void makeRoom(LinkedHashMap<String, V> map, Predicate<V> expired) {
        if (map.size() < maxTrackedKeys) return;
        map.values().removeIf(expired);
        if (map.size() < maxTrackedKeys) {
            warnedFull = false;
            return;
        }
        if (!warnedFull) {
            warnedFull = true;
            log.warn("Giriş sınırı tablosu dolu ({} kayıt); en eski kayıtlar atılıyor", maxTrackedKeys);
        }
        Iterator<V> it = map.values().iterator();
        while (map.size() >= maxTrackedKeys && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** Usernames are compared trimmed and case-insensitively; longer than 128 characters shares one key. */
    static String key(String username) {
        String k = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return k.length() > MAX_KEY_LENGTH ? k.substring(0, MAX_KEY_LENGTH) : k;
    }

    /** IPv4 as is; IPv6 by its /64 network ("2001:db8:1:2::/64"); no address: "unknown". */
    static String ipKey(String ip) {
        if (ip == null || ip.isBlank()) return "unknown";
        if (ip.indexOf(':') < 0) return ip;
        try {
            // brackets: an IPv6 literal is required, so a malformed value fails instead of going to DNS
            InetAddress addr = InetAddress.getByName("[" + ip + "]");
            if (!(addr instanceof Inet6Address)) return addr.getHostAddress(); // IPv4-mapped
            byte[] b = addr.getAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                if (i > 0) sb.append(':');
                sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff)));
            }
            return sb.append("::/64").toString();
        } catch (Exception e) {
            return ip.toLowerCase(Locale.ROOT);
        }
    }
}
