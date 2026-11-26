package com.bilpark.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory limit for plate lookups (citizen debt query, developer plate search), per client IP and per
 * fixed time window. Short plates are cheap to try one by one, so they have a stricter per-IP limit and a
 * ceiling shared by all clients. One app instance (Render Free), so memory is enough; a restart resets it.
 * Settings: {@code bilpark.rate-limit.plate-lookup.*} (application.properties).
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
    private final int shortPerIp;
    private final int shortMaxLength;
    private final int shortGlobal;
    private final int maxTrackedClients;
    private final Clock clock;

    private final Map<String, Window> perClient = new ConcurrentHashMap<>();
    private final Map<String, Window> global = new ConcurrentHashMap<>(); // kept apart: never evicted

    private static final class Window {
        final long start;
        int count;

        Window(long start) { this.start = start; }
    }

    @Autowired
    public PlateLookupRateLimiter(
            @Value("${bilpark.rate-limit.plate-lookup.enabled:true}") boolean enabled,
            @Value("${bilpark.rate-limit.plate-lookup.window-seconds:60}") int windowSeconds,
            @Value("${bilpark.rate-limit.plate-lookup.per-ip:30}") int perIp,
            @Value("${bilpark.rate-limit.plate-lookup.short-per-ip:5}") int shortPerIp,
            @Value("${bilpark.rate-limit.plate-lookup.short-max-length:5}") int shortMaxLength,
            @Value("${bilpark.rate-limit.plate-lookup.short-global:60}") int shortGlobal,
            @Value("${bilpark.rate-limit.plate-lookup.max-tracked-clients:10000}") int maxTrackedClients,
            Clock clock) {
        this.enabled = enabled;
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.perIp = Math.max(1, perIp);
        this.shortPerIp = Math.max(1, shortPerIp);
        this.shortMaxLength = Math.max(0, shortMaxLength);
        this.shortGlobal = Math.max(1, shortGlobal);
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

        // stricter limits first, so a refused short lookup does not use up the general per-IP allowance
        if (isShort(rawPlate)) {
            acquire(perClient, scope + "|short|" + ip, shortPerIp, now);
            acquire(global, scope + "|short", shortGlobal, now);
        }
        acquire(perClient, scope + "|ip|" + ip, perIp, now);
    }

    boolean isShort(String rawPlate) {
        return PlateNormalizer.clean(rawPlate).length() <= shortMaxLength;
    }

    private void acquire(Map<String, Window> map, String key, int limit, long now) {
        long[] retryAfter = {0};
        map.compute(key, (k, w) -> {
            if (w == null || now - w.start >= windowMillis) w = new Window(now);
            if (w.count < limit) {
                w.count++;
            } else {
                retryAfter[0] = (w.start + windowMillis - now + 999) / 1000;
            }
            return w;
        });
        if (retryAfter[0] > 0) throw new RateLimitedException(MESSAGE, retryAfter[0]);
    }

    /** Keeps memory bounded: drops expired windows, and everything per client if still too many. */
    private void evictIfFull(long now) {
        if (perClient.size() < maxTrackedClients) return;
        perClient.entrySet().removeIf(e -> now - e.getValue().start >= windowMillis);
        if (perClient.size() >= maxTrackedClients) perClient.clear(); // the shared short ceiling still holds
    }
}
