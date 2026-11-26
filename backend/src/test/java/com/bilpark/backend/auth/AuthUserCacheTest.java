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
