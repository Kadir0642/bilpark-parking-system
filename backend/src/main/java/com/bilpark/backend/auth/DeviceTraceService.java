package com.bilpark.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Device trace (docs/api-gelistirici-izleme.md section 3): when an authenticated user was last seen, and
 * which app version / device the mobile app reported (X-App-Version, X-Device).
 *
 * At most one database write per user per 2 minutes (last write time is kept in memory). The write is a
 * direct UPDATE on a small background thread: it never slows the request and a failure never reaches it.
 */
@Service
public class DeviceTraceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTraceService.class);

    public static final Duration MIN_INTERVAL = Duration.ofMinutes(2);
    static final int MAX_VERSION = 40;
    static final int MAX_DEVICE = 80;
    private static final int MAX_TRACKED_USERS = 10_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Executor executor;
    private final ConcurrentHashMap<String, Long> lastWriteMillis = new ConcurrentHashMap<>();

    @Autowired
    public DeviceTraceService(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, backgroundExecutor());
    }

    /** Tests pass a direct executor ({@code Runnable::run}) to see the write immediately. */
    public DeviceTraceService(JdbcTemplate jdbc, Clock clock, Executor executor) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.executor = executor;
    }

    private static Executor backgroundExecutor() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "device-trace");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.DiscardPolicy()); // a full queue only skips a trace, never blocks a request
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    /** Called for every authenticated request. Cheap when the user was written within the last 2 minutes. */
    public void touch(String username, String appVersionHeader, String deviceHeader) {
        if (username == null || username.isBlank()) return;
        long now = clock.millis();
        boolean[] due = {false};
        lastWriteMillis.compute(username, (k, prev) -> {
            if (prev == null || now - prev >= MIN_INTERVAL.toMillis() || now < prev) {
                due[0] = true;
                return now;
            }
            return prev;
        });
        if (!due[0]) return;
        if (lastWriteMillis.size() > MAX_TRACKED_USERS) lastWriteMillis.clear(); // bound memory, worst case one extra write

        String version = clean(appVersionHeader, MAX_VERSION);
        String device = clean(deviceHeader, MAX_DEVICE);
        LocalDateTime at = LocalDateTime.now(clock);
        try {
            executor.execute(() -> write(username, at, version, device));
        } catch (RuntimeException rejected) {
            // executor is shutting down: skip the trace
        }
    }

    private void write(String username, LocalDateTime at, String version, String device) {
        try {
            // Without headers (web panel) only last_seen_at changes; the last known version / device stay
            jdbc.update("UPDATE users SET last_seen_at = ?, last_app_version = COALESCE(?, last_app_version), "
                    + "last_device = COALESCE(?, last_device) WHERE username = ?", at, version, device, username);
        } catch (RuntimeException e) {
            log.warn("Device trace could not be stored: {}", e.getClass().getSimpleName());
        }
    }

    /** Header value: control characters out, trimmed, cut to the column size; blank becomes null. */
    static String clean(String raw, int max) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), max));
        for (int i = 0; i < raw.length() && sb.length() < max; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
