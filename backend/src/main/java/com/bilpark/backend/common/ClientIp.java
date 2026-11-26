package com.bilpark.backend.common;

import jakarta.servlet.http.HttpServletRequest;

/** Client address of a request, the same way everywhere (audit rows, rate limits). */
public final class ClientIp {
    private ClientIp() {}

    /** First X-Forwarded-For entry (Render puts the client first), else the socket address; null for no request. */
    public static String of(HttpServletRequest req) {
        if (req == null) return null;
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
