package com.bilpark.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class ClientIpTest {

    private static MockHttpServletRequest req(String remote, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void noHeaderUsesSocketAddress() {
        assertEquals("10.9.9.9", new ClientIp(1).of(req("10.9.9.9", null)));
        assertEquals("10.9.9.9", new ClientIp(1).of(req("10.9.9.9", "  ")));
        assertNull(new ClientIp(1).of(null));
    }

    @Test
    void oneHopTakesTheEntryThePlatformProxyAppended() {
        assertEquals("203.0.113.7", new ClientIp(1).of(req("10.9.9.9", "203.0.113.7")));
        // the client sent its own header; the proxy appended the real address on the right
        assertEquals("203.0.113.7", new ClientIp(1).of(req("10.9.9.9", "1.2.3.4, 203.0.113.7")));
        assertEquals("203.0.113.7", new ClientIp(1).of(req("10.9.9.9", "1.2.3.4,5.6.7.8 , 203.0.113.7")));
        assertEquals("2001:db8::1", new ClientIp(1).of(req("10.9.9.9", "1.2.3.4, 2001:db8::1")));
    }

    @Test
    void twoHopsSkipOneMoreProxy() {
        assertEquals("203.0.113.7", new ClientIp(2).of(req("10.9.9.9", "1.2.3.4, 203.0.113.7, 172.16.0.5")));
        // fewer entries than hops: the leftmost one
        assertEquals("203.0.113.7", new ClientIp(2).of(req("10.9.9.9", "203.0.113.7")));
    }

    @Test
    void zeroHopsIgnoresTheHeader() {
        assertEquals("10.9.9.9", new ClientIp(0).of(req("10.9.9.9", "1.2.3.4")));
    }

    @Test
    void valueThatIsNotAnIpFallsBackToSocketAddress() {
        assertEquals("10.9.9.9", new ClientIp(1).of(req("10.9.9.9", "1.2.3.4, not-an-ip")));
        assertEquals("10.9.9.9", new ClientIp(1).of(req("10.9.9.9", "1.2.3.4, " + "1".repeat(65))));
        assertEquals("10.9.9.9", new ClientIp(1).of(req("10.9.9.9", "1.2.3.4, <script>")));
    }
}
