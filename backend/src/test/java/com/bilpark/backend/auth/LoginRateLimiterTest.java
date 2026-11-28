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
import java.util.concurrent.Future;
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

    // 3 failures per username+IP, 15 min window, locks 60 / 300 / 900 s, alert at 8 failures per username,
    // 10 attempts per IP per 60 s
    private LoginRateLimiter limiter() {
        return limiter(10, 10000);
    }

    private LoginRateLimiter limiter(int perIp, int maxKeys) {
        return new LoginRateLimiter(true, 3, 900, "60, 300, 900", 8, perIp, 60, maxKeys, new ClientIp(1), clock);
    }

    /** One attempt that fails: the refusal from begin, or the failure outcome. */
    private static Object attempt(LoginRateLimiter l, String ip, String user) {
        LoginRateLimiter.Attempt a = l.begin(ip, user);
        if (a.refusal() != null) return a.refusal();
        return l.failed(a);
    }

    private static LoginRateLimiter.Failure fail(LoginRateLimiter l, String ip, String user) {
        Object o = attempt(l, ip, user);
        assertInstanceOf(LoginRateLimiter.Failure.class, o, "expected the password to be checked, got " + o);
        return (LoginRateLimiter.Failure) o;
    }

    private static LoginRateLimiter.Refusal refusedAt(LoginRateLimiter l, String ip, String user) {
        Object o = attempt(l, ip, user);
        if (o instanceof LoginRateLimiter.Failure f) {
            assertNotNull(f.lock(), "expected a refusal");
            return f.lock();
        }
        return (LoginRateLimiter.Refusal) o;
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
        assertEquals(2, fail(l, "10.0.0.1", "tevfik").remainingAttempts());
        assertEquals(1, fail(l, "10.0.0.1", "tevfik").remainingAttempts());
        LoginRateLimiter.Failure last = fail(l, "10.0.0.1", "tevfik");
        LoginRateLimiter.Refusal lock = last.lock();
        assertNotNull(lock);
        assertEquals(LoginRateLimiter.Reason.LOCKED, lock.reason());
        assertEquals("LOGIN_LOCKED", lock.code());
        assertTrue(lock.report(), "the failure that locks is reported once");
        assertEquals(60, lock.retryAfterSeconds());
        assertEquals(3, lock.failures());
        assertEquals("Çok sayıda hatalı deneme yapıldı. Güvenliğiniz için 1 dakika sonra tekrar deneyebilirsiniz.",
                lock.message());

        // while locked: refused before the password check, even the right password, not reported again
        clock.plusSeconds(20);
        LoginRateLimiter.Refusal during = succeed(l, "10.0.0.1", "tevfik");
        assertNotNull(during);
        assertFalse(during.report());
        assertEquals(40, during.retryAfterSeconds());

        // right after each lock ends: second lock 5 min, third and later 15 min (cap)
        long remaining = during.retryAfterSeconds();
        for (long seconds : new long[]{300, 900, 900}) {
            clock.plusSeconds(remaining + 1);
            remaining = seconds;
            fail(l, "10.0.0.1", "tevfik");
            fail(l, "10.0.0.1", "tevfik");
            LoginRateLimiter.Refusal next = fail(l, "10.0.0.1", "tevfik").lock();
            assertNotNull(next);
            assertEquals(seconds, next.retryAfterSeconds());
            assertTrue(next.message().contains(seconds / 60 + " dakika"), next.message());
        }
    }

    @Test
    void aLockOnOneAddressDoesNotLockTheOwnerElsewhere() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 3; i++) fail(l, "203.0.113.66", "admin");
        assertEquals(LoginRateLimiter.Reason.LOCKED, succeed(l, "203.0.113.66", "admin").reason());
        assertNull(succeed(l, "198.51.100.7", "admin"), "the owner on another address still gets in");
        // and has a full count of their own
        assertEquals(2, fail(l, "198.51.100.7", "admin").remainingAttempts());
    }

    @Test
    void manyFailuresFromAllAddressesAreReportedOnceButLockNothing() {
        LoginRateLimiter l = limiter();
        int alerts = 0;
        for (int i = 0; i < 20; i++) {
            LoginRateLimiter.Failure f = fail(l, "10.2.0." + i, "admin");
            assertNull(f.lock(), "one failure per address never locks");
            if (f.alert()) {
                alerts++;
                assertEquals(8, f.usernameFailures());
            }
        }
        assertEquals(1, alerts);
        assertNull(succeed(l, "10.3.0.1", "admin"));
        // next window: reported again
        clock.plusSeconds(900);
        for (int i = 0; i < 7; i++) assertFalse(fail(l, "10.4.0." + i, "admin").alert());
        assertTrue(fail(l, "10.4.0.99", "admin").alert());
    }

    @Test
    void successResetsTheCount() {
        LoginRateLimiter l = limiter();
        fail(l, "10.0.0.1", "tevfik");
        fail(l, "10.0.0.1", "tevfik");
        assertNull(succeed(l, "10.0.0.1", "tevfik"));
        assertEquals(2, fail(l, "10.0.0.1", "tevfik").remainingAttempts());
        assertEquals(1, fail(l, "10.0.0.1", "tevfik").remainingAttempts());
        assertNotNull(fail(l, "10.0.0.1", "tevfik").lock());
    }

    @Test
    void failuresFurtherApartThanTheWindowDoNotAddUp() {
        LoginRateLimiter l = limiter();
        fail(l, "10.0.0.1", "tevfik");
        fail(l, "10.0.0.1", "tevfik");
        clock.plusSeconds(900);
        assertEquals(2, fail(l, "10.0.0.1", "tevfik").remainingAttempts());
        fail(l, "10.0.0.1", "tevfik");
        assertNotNull(fail(l, "10.0.0.1", "tevfik").lock());
    }

    @Test
    void lockLengthStartsOverAfterAQuietWindow() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 2; i++) fail(l, "10.0.0.1", "tevfik");
        assertEquals(60, fail(l, "10.0.0.1", "tevfik").lock().retryAfterSeconds());
        clock.plusSeconds(60 + 900); // lock over, then a whole quiet window
        for (int i = 0; i < 2; i++) assertNull(fail(l, "10.0.0.1", "tevfik").lock());
        assertEquals(60, fail(l, "10.0.0.1", "tevfik").lock().retryAfterSeconds());
    }

    @Test
    void usernameKeyIsTrimmedAndCaseInsensitive() {
        LoginRateLimiter l = limiter();
        fail(l, "10.0.0.1", "Tevfik");
        fail(l, "10.0.0.1", " tevfik ");
        assertNotNull(fail(l, "10.0.0.1", "TEVFIK").lock());
        assertNotNull(succeed(l, "10.0.0.1", "tevfik"));
        assertEquals("", LoginRateLimiter.key(null));
        assertEquals(LoginRateLimiter.MAX_KEY_LENGTH, LoginRateLimiter.key("a".repeat(500)).length());
    }

    @Test
    void ipv6IsKeyedByItsSlash64() {
        assertEquals("2001:db8:1:2::/64", LoginRateLimiter.ipKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd"));
        assertEquals("2001:db8:1:2::/64", LoginRateLimiter.ipKey("2001:DB8:1:2::1"));
        assertEquals("192.0.2.1", LoginRateLimiter.ipKey("::ffff:192.0.2.1"));
        assertEquals("192.0.2.1", LoginRateLimiter.ipKey("192.0.2.1"));
        assertEquals("unknown", LoginRateLimiter.ipKey(null));

        LoginRateLimiter l = limiter();
        fail(l, "2001:db8:1:2::1", "tevfik");
        fail(l, "2001:db8:1:2::2", "tevfik");
        assertNotNull(fail(l, "2001:db8:1:2:ffff::9", "tevfik").lock(), "another address in the same /64");
        assertNull(succeed(l, "2001:db8:1:3::1", "tevfik"), "another /64 is another client");
    }

    @Test
    void perIpCapCountsFailuresAndGivesBackSuccessfulSlots() {
        LoginRateLimiter l = limiter(4, 10000);
        // successful logins from one carrier IP do not fill the window
        for (int i = 0; i < 20; i++) assertNull(succeed(l, "100.64.0.1", "officer" + i));
        // spraying different usernames does
        for (int i = 0; i < 4; i++) fail(l, "100.64.0.1", "guess" + i);
        LoginRateLimiter.Refusal r = refusedAt(l, "100.64.0.1", "guess9");
        assertEquals(LoginRateLimiter.Reason.IP_LIMIT, r.reason());
        assertEquals("RATE_LIMITED", r.code());
        assertTrue(r.report());
        assertEquals(60, r.retryAfterSeconds());
        assertTrue(r.message().contains("1 dakika"), r.message());
        assertFalse(refusedAt(l, "100.64.0.1", "guess10").report(), "reported once per window");
        assertNotNull(succeed(l, "100.64.0.1", "officer1"), "the cap holds for everybody on that IP");
        // another IP is unaffected; the window resets
        assertNull(succeed(l, "100.64.0.2", "officer1"));
        clock.plusSeconds(30);
        assertEquals(30, refusedAt(l, "100.64.0.1", "x").retryAfterSeconds());
        clock.plusSeconds(30);
        assertNull(fail(l, "100.64.0.1", "guess11").lock());
    }

    @Test
    void ipComesFromTheRightmostForwardedEntry() {
        LoginRateLimiter l = limiter(2, 10000);
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
        LoginRateLimiter l = limiter(3, 10000);
        for (int i = 0; i < 10; i++) {
            LoginRateLimiter.Attempt a = l.begin("10.0.0.1", "tevfik");
            assertNull(a.refusal(), "attempt " + i);
            l.abandon(a); // database unavailable
        }
        assertEquals(0, l.trackedUsernames(), "abandoned attempts are not in the all-addresses count either");
        assertEquals(2, fail(l, "10.0.0.1", "tevfik").remainingAttempts());
    }

    @Test
    void settlingTwiceHasNoEffect() {
        LoginRateLimiter l = limiter();
        LoginRateLimiter.Attempt a = l.begin("10.0.0.1", "tevfik");
        assertEquals(2, l.failed(a).remainingAttempts());
        l.abandon(a);
        assertSame(LoginRateLimiter.Failure.NONE, l.failed(a));
        l.succeeded(a);
        assertEquals(1, fail(l, "10.0.0.1", "tevfik").remainingAttempts());
    }

    @Test
    void parallelAttemptsCannotPassTheLimitTogether() throws Exception {
        LoginRateLimiter l = limiter(1000, 10000);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<LoginRateLimiter.Attempt>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                LoginRateLimiter.Attempt a = l.begin("10.0.0.1", "admin");
                if (a.refusal() == null) admitted.incrementAndGet();
                else assertEquals(LoginRateLimiter.Reason.BUSY, a.refusal().reason());
                return a;
            }));
        }
        go.countDown();
        List<LoginRateLimiter.Attempt> started = new ArrayList<>();
        for (var f : futures) started.add(f.get(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(3, admitted.get(), "only max-failures attempts may run at once for one username and address");
        for (LoginRateLimiter.Attempt a : started) if (a.refusal() == null) l.failed(a);
        assertEquals(LoginRateLimiter.Reason.LOCKED, l.begin("10.0.0.1", "admin").refusal().reason());
    }

    @Test
    void clearLiftsTheLocksOfAUsernameOnEveryAddress() {
        LoginRateLimiter l = limiter();
        for (int i = 0; i < 3; i++) fail(l, "10.0.0.1", "tevfik");
        for (int i = 0; i < 3; i++) fail(l, "10.0.0.2", "tevfik");
        for (int i = 0; i < 3; i++) fail(l, "10.0.0.1", "tevfik|10.0.0.1");
        l.clear("Tevfik");
        assertNull(succeed(l, "10.0.0.1", "tevfik"));
        assertNull(succeed(l, "10.0.0.2", "tevfik"));
        assertNotNull(succeed(l, "10.0.0.1", "tevfik|10.0.0.1"), "a different username is not touched");
    }

    @Test
    void fullTablesDropExpiredThenOldestEntries() {
        LoginRateLimiter l = limiter(100000, 5);
        for (int i = 0; i < 3; i++) fail(l, "10.0.0.1", "old");
        for (int i = 0; i < 50; i++) fail(l, "10.1.0." + i, "spray" + i);
        assertTrue(l.trackedPairs() <= 5);
        assertTrue(l.trackedIps() <= 5);
        assertTrue(l.trackedUsernames() <= 5);
        // never fail-open: a new key is always tracked, the least recently used one made room
        LoginRateLimiter.Failure f = fail(l, "10.9.9.9", "fresh");
        assertEquals(2, f.remainingAttempts());
        assertEquals(5, l.trackedPairs());

        // an entry in use stays: it is the most recently used
        LoginRateLimiter m = limiter(100000, 3);
        for (int i = 0; i < 3; i++) fail(m, "10.0.0.1", "hot");
        for (int i = 0; i < 10; i++) {
            fail(m, "10.1.0." + i, "spray" + i);
            assertEquals(LoginRateLimiter.Reason.LOCKED, succeed(m, "10.0.0.1", "hot").reason());
        }

        // expired entries go first
        clock.plusSeconds(900 + 900);
        fail(l, "10.0.0.1", "later");
        assertEquals(1, l.trackedPairs());
    }

    @Test
    void disabledLetsEverythingThrough() {
        LoginRateLimiter l = new LoginRateLimiter(false, 1, 900, "60", 1, 1, 60, 10, new ClientIp(1), clock);
        for (int i = 0; i < 10; i++) assertEquals(-1, fail(l, "10.0.0.1", "tevfik").remainingAttempts());
        assertEquals(0, l.trackedPairs());
    }

    @Test
    void lockListParsing() {
        assertArrayEquals(new long[]{60_000, 300_000}, LoginRateLimiter.parseLocks(" 60 , 300 ,"));
        assertArrayEquals(new long[]{60_000}, LoginRateLimiter.parseLocks(""));
        assertArrayEquals(new long[]{1_000}, LoginRateLimiter.parseLocks("0"));
    }
}
