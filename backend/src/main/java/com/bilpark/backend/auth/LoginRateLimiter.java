package com.bilpark.backend.auth;

import com.bilpark.backend.common.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory brute-force protection for {@code POST /api/auth/login}.
 * <ul>
 *   <li><b>Per username:</b> after {@code max-failures} failed attempts in a row (no gap longer than
 *       {@code failure-window-seconds}) the username is locked for the next length in {@code lock-seconds}
 *       (1 min, 5 min, 15 min by default; the last value repeats). A successful login forgets the username.
 *       Unknown usernames are counted exactly like existing ones, so the answers never tell them apart.</li>
 *   <li><b>Per client IP:</b> at most {@code per-ip} attempts per {@code ip-window-seconds} fixed window. Many
 *       officers share one carrier (CGNAT) address, so this is generous and a successful login gives its slot
 *       back: what fills the window is failed (and still running) attempts, i.e. trying many usernames.</li>
 * </ul>
 * An attempt reserves its slots in {@link #begin} before the password is checked, so parallel requests cannot
 * slip past a limit; {@link #failed}, {@link #succeeded} or {@link #abandon} settles it. An attempt that never
 * reached the password check (database unavailable) is abandoned: it counts as nothing.
 * <p>
 * Memory is bounded by {@code max-tracked-usernames} and {@code max-tracked-ips}. When a map is full, idle
 * entries are dropped first, then (usernames only) entries that are not locked; if it is still full the new key
 * is not tracked. Existing locks are never dropped to make room. One app instance (Render Free); a restart
 * clears everything. Locks are not stored anywhere else: one ends when its time is up, or when someone resets
 * that user's password ({@link #clear}). Settings: {@code bilpark.rate-limit.login.*}.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    static final String LOCKED_MESSAGE = "Çok fazla başarısız giriş denemesi yapıldı. Lütfen %d dakika sonra tekrar deneyin.";
    static final String BUSY_MESSAGE = "Çok fazla giriş denemesi yapıldı. Lütfen biraz bekleyip tekrar deneyin.";
    static final int MAX_KEY_LENGTH = 128;

    /** Why an attempt was refused. */
    public enum Reason { USERNAME_LOCKED, IP_LIMIT }

    /**
     * A refused attempt (429). {@code report} is true once per lock / per IP window: the caller writes one
     * audit row then, not one per refused request.
     */
    public record Refusal(Reason reason, long retryAfterSeconds, String message, boolean report,
                          int failures, long lockSeconds) {}

    /** One login attempt between {@link #begin} and its outcome. */
    public static final class Attempt {
        private final Refusal refusal;
        private final String userKey;      // null: username not tracked
        private final String ipKey;        // null: IP not tracked
        private final long ipWindowStart;
        private boolean settled;

        private Attempt(Refusal refusal, String userKey, String ipKey, long ipWindowStart) {
            this.refusal = refusal;
            this.userKey = userKey;
            this.ipKey = ipKey;
            this.ipWindowStart = ipWindowStart;
            this.settled = refusal != null;
        }

        /** Non-null: refused before the password check, nothing was reserved. */
        public Refusal refusal() { return refusal; }
    }

    private static final class Account {
        int failures;      // failed attempts since the last lock / success
        int pending;       // attempts whose password check is still running
        long lastFailure;
        long lockedUntil;  // epoch millis; locked while now < lockedUntil
        int locks;         // locks so far: picks the next lock length
    }

    private static final class IpWindow {
        final long start;
        int count;
        boolean reported;

        IpWindow(long start) { this.start = start; }
    }

    private final boolean enabled;
    private final int maxFailures;
    private final long failureWindowMillis;
    private final long[] lockMillis;
    private final int perIp;
    private final long ipWindowMillis;
    private final int maxTrackedUsernames;
    private final int maxTrackedIps;
    private final ClientIp clientIp;
    private final Clock clock;

    private final Map<String, Account> accounts = new HashMap<>(); // guarded by this
    private final Map<String, IpWindow> ips = new HashMap<>();      // guarded by this
    private boolean warnedFull;                                     // guarded by this

    @Autowired
    public LoginRateLimiter(
            @Value("${bilpark.rate-limit.login.enabled:true}") boolean enabled,
            @Value("${bilpark.rate-limit.login.max-failures:5}") int maxFailures,
            @Value("${bilpark.rate-limit.login.failure-window-seconds:900}") int failureWindowSeconds,
            @Value("${bilpark.rate-limit.login.lock-seconds:60,300,900}") String lockSeconds,
            @Value("${bilpark.rate-limit.login.per-ip:30}") int perIp,
            @Value("${bilpark.rate-limit.login.ip-window-seconds:60}") int ipWindowSeconds,
            @Value("${bilpark.rate-limit.login.max-tracked-usernames:10000}") int maxTrackedUsernames,
            @Value("${bilpark.rate-limit.login.max-tracked-ips:10000}") int maxTrackedIps,
            ClientIp clientIp,
            Clock clock) {
        this.enabled = enabled;
        this.maxFailures = Math.max(1, maxFailures);
        this.failureWindowMillis = Math.max(1, failureWindowSeconds) * 1000L;
        this.lockMillis = parseLocks(lockSeconds);
        this.perIp = Math.max(1, perIp);
        this.ipWindowMillis = Math.max(1, ipWindowSeconds) * 1000L;
        this.maxTrackedUsernames = Math.max(1, maxTrackedUsernames);
        this.maxTrackedIps = Math.max(1, maxTrackedIps);
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
        if (!enabled) return new Attempt(null, null, null, 0);
        long now = clock.millis();
        String userKey = key(username);
        String ipKey = ip == null || ip.isBlank() ? "unknown" : ip;

        Account acc = accounts.get(userKey);
        if (acc != null && forgettable(acc, now)) {
            accounts.remove(userKey);
            acc = null;
        }
        if (acc != null && acc.failures > 0 && now - acc.lastFailure >= failureWindowMillis) acc.failures = 0;
        if (acc != null && now < acc.lockedUntil) return refused(lockRefusal(acc, now, false));
        // failures plus attempts still being checked: parallel requests cannot pass the limit together
        if (acc != null && acc.failures + acc.pending >= maxFailures) {
            return refused(new Refusal(Reason.USERNAME_LOCKED, 1, BUSY_MESSAGE, false, acc.failures, 0));
        }

        IpWindow w = ips.get(ipKey);
        if (w != null && now - w.start >= ipWindowMillis) {
            ips.remove(ipKey);
            w = null;
        }
        if (w != null && w.count >= perIp) {
            boolean report = !w.reported;
            w.reported = true;
            long retry = Math.max(1, (w.start + ipWindowMillis - now + 999) / 1000);
            return refused(new Refusal(Reason.IP_LIMIT, retry, BUSY_MESSAGE, report, 0, 0));
        }

        // reserve
        if (w == null && makeRoom(ips, maxTrackedIps, x -> now - x.start >= ipWindowMillis, null)) {
            w = new IpWindow(now);
            ips.put(ipKey, w);
        }
        if (w != null) w.count++;

        if (acc == null && makeRoom(accounts, maxTrackedUsernames, x -> forgettable(x, now),
                x -> x.pending == 0 && now >= x.lockedUntil)) {
            acc = new Account();
            accounts.put(userKey, acc);
        }
        if (acc != null) acc.pending++;
        return new Attempt(null, acc != null ? userKey : null, w != null ? ipKey : null, w != null ? w.start : 0);
    }

    /**
     * Wrong username or password. Returns the refusal when this failure locked the username ({@code report}
     * true: write the audit row) or when it was already locked by a parallel attempt; null otherwise (401).
     */
    public synchronized Refusal failed(Attempt a) {
        if (a == null || a.settled) return null;
        a.settled = true;
        if (a.userKey == null) return null;
        Account acc = accounts.get(a.userKey);
        if (acc == null) return null;
        long now = clock.millis();
        if (acc.pending > 0) acc.pending--;
        if (now < acc.lockedUntil) return lockRefusal(acc, now, false);
        if (acc.failures > 0 && now - acc.lastFailure >= failureWindowMillis) acc.failures = 0;
        acc.failures++;
        acc.lastFailure = now;
        if (acc.failures < maxFailures) return null;

        int failures = acc.failures;
        long lock = lockMillis[Math.min(acc.locks, lockMillis.length - 1)];
        acc.locks++;
        acc.failures = 0;
        acc.lockedUntil = now + lock;
        return new Refusal(Reason.USERNAME_LOCKED, lock / 1000, lockedMessage(lock), true, failures, lock / 1000);
    }

    /** Correct password: the username is forgotten, the IP slot is given back. */
    public synchronized void succeeded(Attempt a) {
        if (a == null || a.settled) return;
        a.settled = true;
        if (a.userKey != null) accounts.remove(a.userKey);
        releaseIp(a);
    }

    /** The password was never checked (database unavailable, unexpected error): nothing is counted. No-op once settled. */
    public synchronized void abandon(Attempt a) {
        if (a == null || a.settled) return;
        a.settled = true;
        if (a.userKey != null) {
            Account acc = accounts.get(a.userKey);
            if (acc != null && acc.pending > 0) acc.pending--;
        }
        releaseIp(a);
    }

    /** Lifts a username's lock and failure count (its password was reset by an admin / developer). */
    public synchronized void clear(String username) {
        Account acc = accounts.get(key(username));
        if (acc == null) return;
        acc.failures = 0;
        acc.lockedUntil = 0;
        acc.locks = 0;
    }

    /** Tracked usernames / IPs (tests). */
    synchronized int trackedUsernames() { return accounts.size(); }

    synchronized int trackedIps() { return ips.size(); }

    private void releaseIp(Attempt a) {
        if (a.ipKey == null) return;
        IpWindow w = ips.get(a.ipKey);
        // only the window the slot was taken from: a new window starts with its own count
        if (w != null && w.start == a.ipWindowStart && w.count > 0) w.count--;
    }

    private boolean forgettable(Account acc, long now) {
        return acc.pending == 0 && now >= acc.lockedUntil
                && now - Math.max(acc.lastFailure, acc.lockedUntil) >= failureWindowMillis;
    }

    private Refusal lockRefusal(Account acc, long now, boolean report) {
        long ms = acc.lockedUntil - now;
        return new Refusal(Reason.USERNAME_LOCKED, Math.max(1, (ms + 999) / 1000), lockedMessage(ms), report, 0, 0);
    }

    private static String lockedMessage(long ms) {
        return String.format(Locale.ROOT, LOCKED_MESSAGE, Math.max(1, (ms + 59_999) / 60_000));
    }

    private static Attempt refused(Refusal r) {
        return new Attempt(r, null, null, 0);
    }

    /** True when the map has room for one more key (after dropping idle, then expendable entries). */
    private <V> boolean makeRoom(Map<String, V> map, int max, java.util.function.Predicate<V> idle,
                                 java.util.function.Predicate<V> expendable) {
        if (map.size() < max) return true;
        map.values().removeIf(idle);
        if (map.size() >= max && expendable != null) map.values().removeIf(expendable);
        if (map.size() < max) {
            warnedFull = false;
            return true;
        }
        if (!warnedFull) {
            warnedFull = true;
            log.warn("Giriş sınırı tablosu dolu ({} kayıt); yeni anahtarlar izlenmiyor", max);
        }
        return false;
    }

    /** Usernames are compared trimmed and case-insensitively; longer than 128 characters shares one key. */
    static String key(String username) {
        String k = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return k.length() > MAX_KEY_LENGTH ? k.substring(0, MAX_KEY_LENGTH) : k;
    }
}
