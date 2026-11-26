package com.bilpark.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Short-lived, bounded in-memory cache of the user a JWT belongs to (enabled flag, role, password change time).
 * Without it every authenticated request reads the users table once more before it does any work; under load
 * (0.1 CPU, small pool) that extra read is what turned valid sessions into 401s.
 *
 * Security: entries live at most {@code ttl-seconds} (default 30). Every change to a users row through JPA
 * (password, active flag, role, zone, login time) evicts that user right away and again after the commit
 * ({@link UserCacheEvictionListener}), so deactivation and password reset still take effect immediately on
 * this instance. Login itself never reads from here (it needs the fresh password hash). The cached copy carries
 * no password hash. Single instance only: a second app instance would see changes made elsewhere after the TTL.
 */
@Component
public class AuthUserCache {

    private record Entry(UserDetails user, long expiresAtNanos) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    // Bumped by every eviction BEFORE the entry is removed. A load stores its result only if no eviction
    // happened since the load started: otherwise a read that saw the old row (before the commit) could put it
    // back after the eviction and keep a reset / deactivated user cached for the TTL.
    private final AtomicLong generation = new AtomicLong();
    private final long ttlNanos;
    private final int maxEntries;

    public AuthUserCache(@Value("${bilpark.auth.user-cache.ttl-seconds:30}") long ttlSeconds,
                         @Value("${bilpark.auth.user-cache.max-entries:1000}") int maxEntries) {
        this.ttlNanos = Math.max(0, ttlSeconds) * 1_000_000_000L;
        this.maxEntries = Math.max(1, maxEntries);
    }

    private static String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }

    /** Cached user, or loads it (exceptions of the loader propagate and nothing is cached). */
    public UserDetails get(String username, Function<String, UserDetails> loader) {
        if (ttlNanos == 0) return loader.apply(username);
        String k = key(username);
        long now = System.nanoTime();
        Entry e = entries.get(k);
        if (e != null && now - e.expiresAtNanos() < 0) return e.user();
        long gen = generation.get();
        UserDetails loaded = loader.apply(username);
        if (entries.size() >= maxEntries) prune(now);
        Entry fresh = new Entry(loaded, now + ttlNanos);
        // Atomic per key with evict's remove; the generation check inside rejects a load that overlapped an
        // eviction (evict bumps the generation first, so either the check fails or the remove comes after the put).
        entries.compute(k, (key, old) -> generation.get() == gen ? fresh : old);
        return loaded;
    }

    private void prune(long now) {
        entries.values().removeIf(e -> now - e.expiresAtNanos() >= 0);
        if (entries.size() >= maxEntries) entries.clear(); // still full: start over rather than grow
    }

    /** Drops the user now and, inside a transaction, once more after it commits (a read in between may
     *  have cached the old row). */
    public void evict(String username) {
        String k = key(username);
        drop(k);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    drop(k);
                }
            });
        }
    }

    private void drop(String k) {
        generation.incrementAndGet();
        entries.remove(k);
    }

    public void clear() {
        generation.incrementAndGet();
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
