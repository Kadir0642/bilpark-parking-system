package com.bilpark.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class PlateLookupRateLimiterTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plusSeconds(long s) { now = now.plusSeconds(s); }
    }

    private final MutableClock clock = new MutableClock();

    // window 60 s, 6 lookups per IP, 2 different short plates (<= 5 chars) per IP, 4 different short plates for all
    private PlateLookupRateLimiter limiter(boolean enabled) {
        return limiter(enabled, 4, 10000);
    }

    private PlateLookupRateLimiter limiter(boolean enabled, int shortGlobal, int maxClients) {
        return new PlateLookupRateLimiter(enabled, 60, 6, 2, 5, shortGlobal, maxClients, new ClientIp(1), clock);
    }

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    /** Behind the platform proxy: the client may send its own header, the proxy appends the real address. */
    private static MockHttpServletRequest viaProxy(String clientSent, String real) {
        MockHttpServletRequest r = from("10.9.9.9");
        r.addHeader("X-Forwarded-For", clientSent == null ? real : clientSent + ", " + real);
        return r;
    }

    @Test
    void perIpLimitThen429WithRetryAfterAndWindowReset() {
        PlateLookupRateLimiter l = limiter(true);
        for (int i = 0; i < 6; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "34ABC123");
        RateLimitedException e = assertThrows(RateLimitedException.class,
                () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "34ABC124"));
        assertEquals(429, e.getStatus().value());
        assertEquals(ErrorCodes.RATE_LIMITED, e.getCode());
        assertEquals(60, e.getRetryAfterSeconds());

        // another client is not affected; another scope has its own counters
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.2"), "34ABC123");
        l.check(PlateLookupRateLimiter.DEVELOPER_SEARCH, from("10.0.0.1"), "34ABC123");

        clock.plusSeconds(45);
        RateLimitedException later = assertThrows(RateLimitedException.class,
                () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "34ABC123"));
        assertEquals(15, later.getRetryAfterSeconds());

        clock.plusSeconds(15); // next window
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "34ABC123");
    }

    @Test
    void manyDifferentShortPlatesFromOneIpAreRefused() {
        PlateLookupRateLimiter l = limiter(true);
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB 1");
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "ab-12");
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB13"));
        // plates already asked for in this window still pass (refresh, QR re-scan); full plates too
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB1");
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB12");
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "34ABC123");
    }

    @Test
    void repeatingTheSameShortPlateIsOnlyHeldByTheGeneralLimit() {
        PlateLookupRateLimiter l = limiter(true);
        for (int i = 0; i < 6; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB1");
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB1"));
    }

    @Test
    void differentShortPlatesShareACeilingAcrossClients() {
        PlateLookupRateLimiter l = limiter(true);
        for (int i = 1; i <= 4; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.0.1." + i), "AB" + i);
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.1.9"), "AB9"));
        // a short plate already seen passes from any client; full-length plates are not held back
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.1.9"), "AB1");
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.1.9"), "34ABC123");
    }

    @Test
    void plateRefusedByTheSharedCeilingIsNotRecordedForTheClient() {
        PlateLookupRateLimiter l = limiter(true, 1, 10000);
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.2.1"), "AB1");
        // refused by the shared ceiling: must not use up 10.0.2.2's own allowance of 2 different short plates
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.2.2"), "AB2"));
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.2.2"), "AB3"));
        // with AB2/AB3 wrongly recorded this would be refused by the per-IP limit of 2 different short plates
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.2.2"), "AB1");
    }

    @Test
    void proxyAppendedAddressIsTheClient() {
        PlateLookupRateLimiter l = limiter(true);
        for (int i = 0; i < 6; i++) l.check(PlateLookupRateLimiter.DEBT, viaProxy(null, "203.0.113.7"), "34ABC123");
        assertThrows(RateLimitedException.class,
                () -> l.check(PlateLookupRateLimiter.DEBT, viaProxy(null, "203.0.113.7"), "34ABC123"));
        l.check(PlateLookupRateLimiter.DEBT, viaProxy(null, "203.0.113.8"), "34ABC123");
    }

    @Test
    void spoofedForwardedForCannotEscapeTheLimit() {
        PlateLookupRateLimiter l = limiter(true);
        // a new fake address on every request: still counted under the real address the proxy appended
        for (int i = 0; i < 6; i++) l.check(PlateLookupRateLimiter.DEBT, viaProxy("1.2.3." + i, "203.0.113.7"), "34ABC123");
        assertThrows(RateLimitedException.class,
                () -> l.check(PlateLookupRateLimiter.DEBT, viaProxy("1.2.3.99", "203.0.113.7"), "34ABC123"));
    }

    @Test
    void spoofedForwardedForCannotBurnSomeoneElsesQuota() {
        PlateLookupRateLimiter l = limiter(true);
        for (int i = 0; i < 6; i++) l.check(PlateLookupRateLimiter.DEBT, viaProxy("1.2.3.4", "198.51.100.66"), "34ABC123");
        // the real 1.2.3.4 is untouched
        l.check(PlateLookupRateLimiter.DEBT, viaProxy(null, "1.2.3.4"), "34ABC123");
    }

    @Test
    void disabledNeverLimits() {
        PlateLookupRateLimiter l = limiter(false);
        for (int i = 0; i < 50; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB" + i);
    }

    @Test
    void capCountsClientsNotKeys() {
        PlateLookupRateLimiter l = limiter(true, 1000, 3);
        for (int i = 1; i <= 3; i++) {
            l.check(PlateLookupRateLimiter.DEBT, from("10.4.0." + i), "AB" + i);       // two windows per client
            l.check(PlateLookupRateLimiter.DEVELOPER_SEARCH, from("10.4.0." + i), "34ABC123");
        }
        assertEquals(3, l.trackedClients());
    }

    @Test
    void whenFullNewClientsShareAnOverflowBucketAndTrackedClientsKeepTheirCounters() {
        PlateLookupRateLimiter l = limiter(true, 1000, 2);
        for (int i = 0; i < 5; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.1"), "34ABC123");
        l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.2"), "34ABC123");

        // full: two new clients share one bucket of 6
        for (int i = 0; i < 3; i++) {
            l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.3"), "34ABC123");
            l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.4"), "34ABC123");
        }
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.5"), "34ABC123"));
        assertEquals(2, l.trackedClients());

        // the tracked client was not reset: one lookup left, then refused
        l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.1"), "34ABC123");
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.1"), "34ABC123"));

        // after the window, expired clients are dropped and new ones get their own counters again
        clock.plusSeconds(60);
        l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.6"), "34ABC123");
        assertTrue(l.trackedClients() <= 2);
        for (int i = 0; i < 5; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.5.0.6"), "34ABC123");
    }

    @Test
    void sharedShortCeilingIsNotAffectedByTheClientCap() {
        PlateLookupRateLimiter l = limiter(true, 4, 100);
        for (int i = 0; i < 4; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.1.0." + i), "AB" + i);
        for (int i = 0; i < 300; i++) {
            try {
                l.check(PlateLookupRateLimiter.DEBT, from("10.2." + (i / 250) + "." + (i % 250)), "34ABC12" + (i % 10));
            } catch (RateLimitedException ignored) {
                // past the cap new clients share the overflow bucket
            }
        }
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.3.0.1"), "AB9"));
    }
}
