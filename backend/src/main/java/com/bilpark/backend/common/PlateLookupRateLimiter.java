package com.bilpark.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory limit for plate lookups (citizen debt query, developer plate search), per client IP and per
 * fixed time window. Many real users can share one public IP (mobile carrier NAT), so the plain request
 * limit is generous. What is strict is the trying-plates-one-by-one pattern: the number of DIFFERENT short
 * plates looked up from one IP, plus a ceiling of different short plates shared by all clients. Asking for
 * the same plate again (refresh, QR re-scan) never counts against the short limits.
 * One app instance (Render Free), so memory is enough; a restart resets it.
 * Settings: {@code bilpark.rate-limit.plate-lookup.*} (application.properties). Officer / admin endpoints
 * are not limited here.
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
    private final Clock clock;

    private final Map<String, Window> perClient = new ConcurrentHashMap<>();
    private final Map<String, Window> global = new ConcurrentHashMap<>(); // kept apart: never evicted

    private static final class Window {
        final long start;
        int count;
        Set<String> plates; // distinct plates of this window (short-plate keys only), at most the limit

        Window(long start) { this.start = start; }
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
            Clock clock) {
        this.enabled = enabled;
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.perIp = Math.max(1, perIp);
        this.shortDistinctPerIp = Math.max(1, shortDistinctPerIp);
        this.shortMaxLength = Math.max(0, shortMaxLength);
        this.shortDistinctGlobal = Math.max(1, shortDistinctGlobal);
        this.maxTrackedClients = Math.max(100, maxTrackedClients);
        this.clock = clock;
    }

    /** Counts one lookup of {@code rawPlate}; throws 429 {@link RateLimitedException} when over a limit. */
    public void check(String scope, HttpServletRequest request, String rawPlate) {
        if (!enabled) return;
        String ip = ClientIp.of(request);
        if (ip == null || ip.isBlank()) ip = "unknown";
        long now = clock.millis();
        evictIfFull(now);

        String plate = PlateNormalizer.clean(rawPlate);
        // stricter limits first, so a refused short lookup does not use up the general per-IP allowance
        if (plate.length() <= shortMaxLength) {
            acquireDistinct(perClient, scope + "|short|" + ip, plate, shortDistinctPerIp, now);
            acquireDistinct(global, scope + "|short", plate, shortDistinctGlobal, now);
        }
        acquire(perClient, scope + "|ip|" + ip, perIp, now);
    }

    private void acquire(Map<String, Window> map, String key, int limit, long now) {
        long[] retryAfter = {0};
        map.compute(key, (k, w) -> {
            if (w == null || now - w.start >= windowMillis) w = new Window(now);
            if (w.count < limit) {
                w.count++;
            } else {
                retryAfter[0] = retryAfter(w, now);
            }
            return w;
        });
        if (retryAfter[0] > 0) throw new RateLimitedException(MESSAGE, retryAfter[0]);
    }

    /** A plate already seen in this window passes; a new one passes while fewer than {@code limit} were seen. */
    private void acquireDistinct(Map<String, Window> map, String key, String plate, int limit, long now) {
        long[] retryAfter = {0};
        map.compute(key, (k, w) -> {
            if (w == null || now - w.start >= windowMillis) w = new Window(now);
            if (w.plates == null) w.plates = new HashSet<>();
            if (!w.plates.contains(plate)) {
                if (w.plates.size() < limit) {
                    w.plates.add(plate);
                } else {
                    retryAfter[0] = retryAfter(w, now);
                }
            }
            return w;
        });
        if (retryAfter[0] > 0) throw new RateLimitedException(MESSAGE, retryAfter[0]);
    }

    private long retryAfter(Window w, long now) {
        return Math.max(1, (w.start + windowMillis - now + 999) / 1000);
    }

    /** Keeps memory bounded: drops expired windows, and everything per client if still too many. */
    private void evictIfFull(long now) {
        if (perClient.size() < maxTrackedClients) return;
        perClient.entrySet().removeIf(e -> now - e.getValue().start >= windowMillis);
        if (perClient.size() >= maxTrackedClients) perClient.clear(); // the shared short ceiling still holds
    }
}
