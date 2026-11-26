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

    // window 60 s, 3 per IP, 2 short per IP (<= 5 chars), 4 short for all clients
    private PlateLookupRateLimiter limiter(boolean enabled) {
        return new PlateLookupRateLimiter(enabled, 60, 3, 2, 5, 4, 10000, clock);
    }

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    @Test
    void perIpLimitThen429WithRetryAfterAndWindowReset() {
        PlateLookupRateLimiter l = limiter(true);
        for (int i = 0; i < 3; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "34ABC123");
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
    void shortPlatesHaveStricterPerIpLimit() {
        PlateLookupRateLimiter l = limiter(true);
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB 1");
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "ab-12");
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB13"));
    }

    @Test
    void shortPlatesShareACeilingAcrossClients() {
        PlateLookupRateLimiter l = limiter(true);
        for (int i = 1; i <= 4; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.0.1." + i), "AB1");
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.0.1.9"), "AB2"));
        // full-length plates are not held back by the short ceiling
        l.check(PlateLookupRateLimiter.DEBT, from("10.0.1.9"), "34ABC123");
    }

    @Test
    void forwardedForFirstEntryIsTheClient() {
        PlateLookupRateLimiter l = limiter(true);
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = from("10.9.9.9"); // proxy
            r.addHeader("X-Forwarded-For", "203.0.113.7, 10.9.9.9");
            l.check(PlateLookupRateLimiter.DEBT, r, "34ABC123");
        }
        MockHttpServletRequest same = from("10.9.9.9");
        same.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, same, "34ABC123"));
        MockHttpServletRequest other = from("10.9.9.9");
        other.addHeader("X-Forwarded-For", "203.0.113.8");
        l.check(PlateLookupRateLimiter.DEBT, other, "34ABC123");
    }

    @Test
    void disabledNeverLimits() {
        PlateLookupRateLimiter l = limiter(false);
        for (int i = 0; i < 50; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.0.0.1"), "AB1");
    }

    @Test
    void manyClientsStayBoundedAndShortCeilingSurvivesEviction() {
        PlateLookupRateLimiter l = new PlateLookupRateLimiter(true, 60, 3, 2, 5, 4, 100, clock);
        for (int i = 0; i < 4; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.1.0." + i), "AB1");
        for (int i = 0; i < 300; i++) l.check(PlateLookupRateLimiter.DEBT, from("10.2." + (i / 250) + "." + (i % 250)), "34ABC123");
        assertThrows(RateLimitedException.class, () -> l.check(PlateLookupRateLimiter.DEBT, from("10.3.0.1"), "AB1"));
    }
}
