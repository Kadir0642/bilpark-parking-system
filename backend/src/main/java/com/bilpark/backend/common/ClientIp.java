package com.bilpark.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Client address of a request, the same way everywhere (audit rows, rate limits).
 * <p>
 * The left part of X-Forwarded-For is whatever the client sent; only the entries appended by our own proxies
 * can be trusted. So the address is taken counting from the RIGHT: {@code bilpark.client-ip.trusted-proxy-hops}
 * = 1 (default) takes the rightmost entry, the one the hosting proxy appends; 2 skips one more proxy in front;
 * 0 ignores the header. Without the header, or with a value that does not look like an IP address, the socket
 * address is used.
 */
@Component
public class ClientIp {

    static final int MAX_LENGTH = 64;
    private static final Pattern IP_LIKE = Pattern.compile("^[0-9A-Fa-f:.]{2," + MAX_LENGTH + "}$");

    private final int trustedProxyHops;

    public ClientIp(@Value("${bilpark.client-ip.trusted-proxy-hops:1}") int trustedProxyHops) {
        this.trustedProxyHops = Math.max(0, trustedProxyHops);
    }

    /** Client address, at most {@value #MAX_LENGTH} characters; null for no request. */
    public String of(HttpServletRequest req) {
        if (req == null) return null;
        String remote = clean(req.getRemoteAddr());
        String fwd = req.getHeader("X-Forwarded-For");
        if (trustedProxyHops == 0 || fwd == null || fwd.isBlank()) return remote;
        String[] parts = fwd.split(",");
        // fewer entries than hops: the leftmost one is the best there is
        String candidate = parts[Math.max(0, parts.length - trustedProxyHops)].trim();
        String ip = clean(candidate);
        return ip != null ? ip : remote;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return IP_LIKE.matcher(t).matches() ? t : null;
    }
}
