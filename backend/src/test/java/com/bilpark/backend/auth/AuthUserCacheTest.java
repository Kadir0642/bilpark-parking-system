package com.bilpark.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AuthUserCacheTest {

    private static UserDetails user(String name) {
        return new BilparkUserDetails(name, "", true, List.of(), null);
    }

    private static Function<String, UserDetails> counting(AtomicInteger calls) {
        return u -> { calls.incrementAndGet(); return user(u); };
    }

    @Test
    void secondLookupWithinTtlIsServedFromCache() {
        AuthUserCache cache = new AuthUserCache(30, 100);
        AtomicInteger calls = new AtomicInteger();
        cache.get("tevfik", counting(calls));
        cache.get("TEVFIK", counting(calls)); // usernames are case-insensitive keys
        assertEquals(1, calls.get());
    }

    @Test
    void evictForcesReload() {
        AuthUserCache cache = new AuthUserCache(30, 100);
        AtomicInteger calls = new AtomicInteger();
        cache.get("tevfik", counting(calls));
        cache.evict("tevfik");
        cache.get("tevfik", counting(calls));
        assertEquals(2, calls.get());
    }

    @Test
    void loadThatOverlapsAnEvictionIsNotStored() {
        // Request A reads the old row (before the password reset commits); meanwhile the reset evicts the user
        // (and again after commit). A must not put its stale copy back: the next request has to reload.
        AuthUserCache cache = new AuthUserCache(30, 100);
        AtomicInteger calls = new AtomicInteger();
        UserDetails stale = cache.get("tevfik", u -> {
            calls.incrementAndGet();
            cache.evict("tevfik"); // the eviction lands while A is still loading
            return user(u);
        });
        assertNotNull(stale);
        assertEquals(0, cache.size());
        cache.get("tevfik", counting(calls));
        assertEquals(2, calls.get(), "stale load must not have been cached");
        cache.get("tevfik", counting(calls));
        assertEquals(2, calls.get(), "a load without a concurrent eviction is cached as usual");
    }

    @Test
    void afterCommitEvictionAlsoBlocksAnOverlappingLoad() {
        AuthUserCache cache = new AuthUserCache(30, 100);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evict("tevfik"); // inside the reset transaction: registers the after-commit eviction
            AtomicInteger calls = new AtomicInteger();
            cache.get("tevfik", u -> {
                calls.incrementAndGet();
                // commit completes while this request is loading the (old) row
                org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                        .forEach(s -> s.afterCompletion(
                                org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
                return user(u);
            });
            assertEquals(0, cache.size());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void zeroTtlDisablesTheCache() {
        AuthUserCache cache = new AuthUserCache(0, 100);
        AtomicInteger calls = new AtomicInteger();
        cache.get("tevfik", counting(calls));
        cache.get("tevfik", counting(calls));
        assertEquals(2, calls.get());
        assertEquals(0, cache.size());
    }

    @Test
    void loaderFailureIsNotCached() {
        AuthUserCache cache = new AuthUserCache(30, 100);
        assertThrows(UsernameNotFoundException.class, () -> cache.get("yok", u -> {
            throw new UsernameNotFoundException(u);
        }));
        assertThrows(IllegalStateException.class, () -> cache.get("db", u -> {
            throw new IllegalStateException("db down");
        }));
        assertEquals(0, cache.size());
    }

    @Test
    void sizeStaysBounded() {
        AuthUserCache cache = new AuthUserCache(30, 10);
        for (int i = 0; i < 100; i++) cache.get("u" + i, AuthUserCacheTest::user);
        assertTrue(cache.size() <= 10, "size " + cache.size());
    }
}
