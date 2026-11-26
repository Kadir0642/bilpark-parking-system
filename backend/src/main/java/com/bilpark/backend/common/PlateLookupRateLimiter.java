package com.bilpark.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory limit for plate lookups (citizen debt query, developer plate search), per client IP and per
 * fixed time window. Many real users can share one public IP (mobile carrier NAT), so the plain request
 * limit is generous. What is strict is the trying-plates-one-by-one pattern: the number of DIFFERENT short
 * plates looked up from one IP, plus a ceiling of different short plates shared by all clients. Asking for
 * the same plate again (refresh, QR re-scan) never counts against the short limits.
 * <p>
 * At most {@code max-tracked-clients} client IPs are tracked. When full, clients whose windows have all
 * expired are dropped; if it is still full, new clients share one overflow bucket (tracked clients keep
 * their own counters, nobody's counters are reset).
 * <p>
 * One app instance (Render Free), so memory is enough; a restart resets it. The work per call is a few map
 * operations, so one lock keeps the counters consistent. Settings: {@code bilpark.rate-limit.plate-lookup.*}
 * (application.properties). Officer / admin endpoints are not limited here.
 */
@Component
public class PlateLookupRateLimiter {

    /** Counter scopes: each endpoint has its own counters. */
    public static final String DEBT = "debt";
    public static final String DEVELOPER_SEARCH = "developer-search";

    static final String MESSAGE = "Çok fazla plaka sorgusu yapıldı. Lütfen biraz bekleyip tekrar deneyin.";

    private final boolean enabled;
    private final long windowMillis;
    private final int perIp;
    private final int shortDistinctPerIp;
    private final int shortMaxLength;
    private final int shortDistinctGlobal;
    private final int maxTrackedClients;
    private final ClientIp clientIp;
    private final Clock clock;

    private final Map<String, Client> clients = new HashMap<>();   // guarded by this
    private final Client overflow = new Client();                  // new clients once the map is full
    private final Map<String, Window> global = new HashMap<>();    // scope -> different short plates of all clients

    private static final class Window {
        final long start;
        int count;
        final Set<String> plates = new HashSet<>(); // distinct short plates of this window, at most the limit

        Window(long start) { this.start = start; }
    }

    /** Windows of one client IP, by scope and kind. */
    private static final class Client {
        final Map<String, Window> windows = new HashMap<>();
        long lastStart;
    }

    @Autowired
    public PlateLookupRateLimiter(
            @Value("${bilpark.rate-limit.plate-lookup.enabled:true}") boolean enabled,
            @Value("${bilpark.rate-limit.plate-lookup.window-seconds:60}") int windowSeconds,
            @Value("${bilpark.rate-limit.plate-lookup.per-ip:120}") int perIp,
            @Value("${bilpark.rate-limit.plate-lookup.short-distinct-per-ip:10}") int shortDistinctPerIp,
            @Value("${bilpark.rate-limit.plate-lookup.short-max-length:5}") int shortMaxLength,
            @Value("${bilpark.rate-limit.plate-lookup.short-distinct-global:100}") int shortDistinctGlobal,
            @Value("${bilpark.rate-limit.plate-lookup.max-tracked-clients:10000}") int maxTrackedClients,
            ClientIp clientIp,
            Clock clock) {
        this.enabled = enabled;
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.perIp = Math.max(1, perIp);
        this.shortDistinctPerIp = Math.max(1, shortDistinctPerIp);
        this.shortMaxLength = Math.max(0, shortMaxLength);
        this.shortDistinctGlobal = Math.max(1, shortDistinctGlobal);
        this.maxTrackedClients = Math.max(1, maxTrackedClients);
        this.clientIp = clientIp;
        this.clock = clock;
    }

    /** Counts one lookup of {@code rawPlate}; throws 429 {@link RateLimitedException} when over a limit. */
    public void check(String scope, HttpServletRequest request, String rawPlate) {
        if (!enabled) return;
        String ip = clientIp.of(request); // validated, at most 64 characters
        if (ip == null || ip.isBlank()) ip = "unknown";
        String plate = PlateNormalizer.clean(rawPlate);
        boolean isShort = plate.length() <= shortMaxLength;
        long now = clock.millis();

        synchronized (this) {
            Client client = clientFor(ip, now);
            client.lastStart = Math.max(client.lastStart, now);
            Window all = window(client.windows, scope + "|all", now);
            Window mine = isShort ? window(client.windows, scope + "|short", now) : null;
            Window everyone = isShort ? window(global, scope, now) : null;

            // every check first, then count: a refused lookup changes no counter (sets stay consistent)
            if (isShort) {
                if (!mine.plates.contains(plate) && mine.plates.size() >= shortDistinctPerIp) throw limited(mine, now);
                if (!everyone.plates.contains(plate) && everyone.plates.size() >= shortDistinctGlobal) {
                    throw limited(everyone, now);
                }
            }
            if (all.count >= perIp) throw limited(all, now);

            all.count++;
            if (isShort) {
                mine.plates.add(plate);
                everyone.plates.add(plate);
            }
        }
    }

    /** Number of client IPs currently tracked (the overflow bucket not included). */
    synchronized int trackedClients() {
        return clients.size();
    }

    private Client clientFor(String ip, long now) {
        Client c = clients.get(ip);
        if (c != null) return c;
        if (clients.size() >= maxTrackedClients) {
            clients.values().removeIf(x -> now - x.lastStart >= windowMillis);
            if (clients.size() >= maxTrackedClients) return overflow;
        }
        c = new Client();
        clients.put(ip, c);
        return c;
    }

    private Window window(Map<String, Window> map, String key, long now) {
        Window w = map.get(key);
        if (w == null || now - w.start >= windowMillis) {
            w = new Window(now);
            map.put(key, w);
        }
        return w;
    }

    private RateLimitedException limited(Window w, long now) {
        return new RateLimitedException(MESSAGE, Math.max(1, (w.start + windowMillis - now + 999) / 1000));
    }
}
