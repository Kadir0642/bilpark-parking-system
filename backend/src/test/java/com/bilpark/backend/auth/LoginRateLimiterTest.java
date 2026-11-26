package com.bilpark.backend.auth;

import com.bilpark.backend.common.ClientIp;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plusSeconds(long s) { now = now.plusSeconds(s); }
    }

    private final MutableClock clock = new MutableClock();

    // 3 failures, 15 min failure window, locks 60 s / 300 s / 900 s, 10 attempts per IP per 60 s
    private LoginRateLimiter limiter() {
        return limiter(10, 10000, 10000);
    }

    private LoginRateLimiter limiter(int perIp, int maxUsers, int maxIps) {
        return new LoginRateLimiter(true, 3, 900, "60, 300, 900", perIp, 60, maxUsers, maxIps, new ClientIp(1), clock);
    }

    /** One attempt that fails; returns the refusal (begin or the lock this failure caused), null for a plain 401. */
    private static LoginRateLimiter.Refusal fail(LoginRateLimiter l, String ip, String user) {
        LoginRateLimiter.Attempt a = l.begin(ip, user);
        if (a.refusal() != null) return a.refusal();
        return l.failed(a);
    }

    private static LoginRateLimiter.Refusal succeed(LoginRateLimiter l, String ip, String user) {
        LoginRateLimiter.Attempt a = l.begin(ip, user);
        if (a.refusal() != null) return a.refusal();
        l.succeeded(a);
        return null;
    }

    @Test
    void locksAfterNFailuresWithGrowingLengthsAndCap() {
        LoginRateLimiter l = limiter();
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNull(fail(l, "10.0.0.2", "tevfik"));
        LoginRateLimiter.Refusal lock = fail(l, "10.0.0.3", "tevfik");
        assertNotNull(lock);
        assertEquals(LoginRateLimiter.Reason.USERNAME_LOCKED, lock.reason());
        assertTrue(lock.report(), "the failure that locks is reported once");
        assertEquals(60, lock.retryAfterSeconds());
        assertEquals(3, lock.failures());
        assertTrue(lock.message().contains("1 dakika"), lock.message());

        // while locked: refused before the password check, even the right password, not reported again
        clock.plusSeconds(20);
        LoginRateLimiter.Refusal during = succeed(l, "10.0.0.4", "tevfik");
        assertNotNull(during);
        assertFalse(during.report());
        assertEquals(40, during.retryAfterSeconds());

        // right after each lock ends: second lock 5 min, third and later 15 min (cap)
        long remaining = during.retryAfterSeconds();
        long[] expected = {300, 900, 900};
        for (long seconds : expected) {
            clock.plusSeconds(remaining + 1);
            remaining = seconds;
            assertNull(fail(l, "10.0.0.1", "tevfik"));
            assertNull(fail(l, "10.0.0.1", "tevfik"));
            LoginRateLimiter.Refusal next = fail(l, "10.0.0.1", "tevfik");
            assertNotNull(next);
            assertEquals(seconds, next.retryAfterSeconds());
            assertEquals(seconds, next.lockSeconds());
        }
    }

    @Test
    void successResetsTheCount() {
        LoginRateLimiter l = limiter();
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNull(succeed(l, "10.0.0.1", "tevfik"));
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNotNull(fail(l, "10.0.0.1", "tevfik"));
        assertEquals(1, l.trackedUsernames());
    }

    @Test
    void failuresFurtherApartThanTheWindowDoNotAddUp() {
        LoginRateLimiter l = limiter();
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        clock.plusSeconds(900);
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNotNull(fail(l, "10.0.0.1", "tevfik"));
    }

    @Test
    void lockLengthStartsOverAfterAQuietWindow() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 2; i++) fail(l, "10.0.0.1", "tevfik");
        assertEquals(60, fail(l, "10.0.0.1", "tevfik").retryAfterSeconds());
        clock.plusSeconds(60 + 900); // lock over, then a whole quiet window
        for (int i = 0; i < 2; i++) assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertEquals(60, fail(l, "10.0.0.1", "tevfik").retryAfterSeconds());
    }

    @Test
    void usernameKeyIsTrimmedAndCaseInsensitive() {
        LoginRateLimiter l = limiter();
        assertNull(fail(l, "10.0.0.1", "Tevfik"));
        assertNull(fail(l, "10.0.0.1", " tevfik "));
        assertNotNull(fail(l, "10.0.0.1", "TEVFIK"));
        assertNotNull(succeed(l, "10.0.0.1", "tevfik"));
        assertEquals("", LoginRateLimiter.key(null));
        assertEquals(LoginRateLimiter.MAX_KEY_LENGTH, LoginRateLimiter.key("a".repeat(500)).length());
    }

    @Test
    void perIpCapCountsFailuresAndGivesBackSuccessfulSlots() {
        LoginRateLimiter l = limiter(4, 10000, 10000);
        // successful logins from one carrier IP do not fill the window
        for (int i = 0; i < 20; i++) assertNull(succeed(l, "100.64.0.1", "officer" + i));
        // spraying different usernames does
        for (int i = 0; i < 4; i++) assertNull(fail(l, "100.64.0.1", "guess" + i));
        LoginRateLimiter.Refusal r = fail(l, "100.64.0.1", "guess9");
        assertNotNull(r);
        assertEquals(LoginRateLimiter.Reason.IP_LIMIT, r.reason());
        assertTrue(r.report());
        assertEquals(60, r.retryAfterSeconds());
        assertFalse(fail(l, "100.64.0.1", "guess10").report(), "reported once per window");
        assertNotNull(succeed(l, "100.64.0.1", "officer1"), "the cap holds for everybody on that IP");
        // another IP is unaffected; the window resets
        assertNull(succeed(l, "100.64.0.2", "officer1"));
        clock.plusSeconds(30);
        assertEquals(30, fail(l, "100.64.0.1", "x").retryAfterSeconds());
        clock.plusSeconds(30);
        assertNull(fail(l, "100.64.0.1", "guess11"));
    }

    @Test
    void ipComesFromTheRightmostForwardedEntry() {
        LoginRateLimiter l = limiter(2, 10000, 10000);
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest r = new MockHttpServletRequest();
            r.addHeader("X-Forwarded-For", "1.1.1." + i + ", 203.0.113.9");
            LoginRateLimiter.Attempt a = l.begin(r, "u" + i);
            assertNull(a.refusal());
            l.failed(a);
        }
        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.9");
        assertEquals(LoginRateLimiter.Reason.IP_LIMIT, l.begin(spoofed, "u9").refusal().reason());
    }

    @Test
    void abandonedAttemptCountsNothing() {
        LoginRateLimiter l = limiter(3, 10000, 10000);
        for (int i = 0; i < 10; i++) {
            LoginRateLimiter.Attempt a = l.begin("10.0.0.1", "tevfik");
            assertNull(a.refusal(), "attempt " + i);
            l.abandon(a); // database unavailable
        }
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNotNull(fail(l, "10.0.0.1", "tevfik"));
    }

    @Test
    void settlingTwiceHasNoEffect() {
        LoginRateLimiter l = limiter();
        LoginRateLimiter.Attempt a = l.begin("10.0.0.1", "tevfik");
        assertNull(l.failed(a));
        l.abandon(a);
        assertNull(l.failed(a));
        l.succeeded(a);
        assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertNotNull(fail(l, "10.0.0.1", "tevfik"), "only two failures were counted, so the third locks");
    }

    @Test
    void parallelAttemptsCannotPassTheLimitTogether() throws Exception {
        LoginRateLimiter l = limiter(1000, 10000, 10000);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<LoginRateLimiter.Attempt> started = new ArrayList<>();
        AtomicInteger admitted = new AtomicInteger();
        List<java.util.concurrent.Future<LoginRateLimiter.Attempt>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                LoginRateLimiter.Attempt a = l.begin("10.0.0." + Thread.currentThread().threadId() % 200, "admin");
                if (a.refusal() == null) admitted.incrementAndGet();
                return a;
            }));
        }
        go.countDown();
        for (var f : futures) started.add(f.get(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(3, admitted.get(), "only max-failures attempts may run at once for one username");
        for (LoginRateLimiter.Attempt a : started) if (a.refusal() == null) l.failed(a);
        assertNotNull(l.begin("10.0.0.1", "admin").refusal());
    }

    @Test
    void clearLiftsALock() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 3; i++) fail(l, "10.0.0.1", "tevfik");
        assertNotNull(succeed(l, "10.0.0.1", "tevfik"));
        l.clear("Tevfik");
        assertNull(succeed(l, "10.0.0.1", "tevfik"));
    }

    @Test
    void memoryIsBoundedAndLocksAreKept() {
        LoginRateLimiter l = limiter(100000, 5, 3);
        for (int i = 0; i < 3; i++) fail(l, "10.0.0.1", "locked");
        // fill with partial counts from many IPs: the map never grows past its limit
        for (int i = 0; i < 50; i++) fail(l, "10.1.0." + i, "spray" + i);
        assertTrue(l.trackedUsernames() <= 5);
        assertTrue(l.trackedIps() <= 3);
        assertNotNull(succeed(l, "10.0.0.1", "locked"), "an existing lock is never dropped to make room");

        // all slots locked: a new username is not tracked (allowed), existing locks stay
        for (int u = 0; u < 4; u++) for (int i = 0; i < 3; i++) fail(l, "10.0.0.1", "lock" + u);
        assertEquals(5, l.trackedUsernames());
        for (int i = 0; i < 10; i++) assertNull(fail(l, "10.0.0.1", "fresh"));
        assertNotNull(succeed(l, "10.0.0.1", "lock0"));

        // idle entries are dropped once their window has passed
        clock.plusSeconds(900 + 900);
        assertNull(fail(l, "10.0.0.1", "later"));
        assertEquals(1, l.trackedUsernames());
    }

    @Test
    void disabledLetsEverythingThrough() {
        LoginRateLimiter l = new LoginRateLimiter(false, 1, 900, "60", 1, 60, 10, 10, new ClientIp(1), clock);
        for (int i = 0; i < 10; i++) assertNull(fail(l, "10.0.0.1", "tevfik"));
        assertEquals(0, l.trackedUsernames());
    }

    @Test
    void lockListParsing() {
        assertArrayEquals(new long[]{60_000, 300_000}, LoginRateLimiter.parseLocks(" 60 , 300 ,"));
        assertArrayEquals(new long[]{60_000}, LoginRateLimiter.parseLocks(""));
        assertArrayEquals(new long[]{1_000}, LoginRateLimiter.parseLocks("0"));
    }
}
