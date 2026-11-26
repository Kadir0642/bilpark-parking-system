package com.bilpark.backend.common;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Overload answers: when the database cannot be reached in time (pool exhausted, connection refused, server
 * not accepting connections) the request gets 503 SERVICE_UNAVAILABLE with Retry-After, never 401 or 500.
 * Clients keep the session and retry after the given seconds.
 */
public final class Overload {

    private Overload() {}

    public static final long RETRY_AFTER_SECONDS = 5;
    public static final String MESSAGE = "Sunucu şu anda yoğun. Lütfen birkaç saniye sonra tekrar deneyin.";

    /**
     * True when the cause chain says "no database connection": Hikari's pool timeout
     * (SQLTransientConnectionException), SQLState class 08 (connection exception), 53300 (too many connections),
     * 57P01-57P03 (server shutting down / cannot connect now), or Spring's CannotGetJdbcConnectionException /
     * CannotCreateTransactionException / Hibernate's JDBCConnectionException.
     */
    public static boolean isDatabaseUnavailable(Throwable ex) {
        int depth = 0;
        for (Throwable t = ex; t != null && depth < 20; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof SQLTransientConnectionException) return true;
            if (t instanceof SQLException sql && isConnectionState(sql.getSQLState())) return true;
            String n = t.getClass().getName();
            if (n.equals("org.springframework.jdbc.CannotGetJdbcConnectionException")
                    || n.equals("org.springframework.transaction.CannotCreateTransactionException")
                    || n.equals("org.hibernate.exception.JDBCConnectionException")) {
                return true;
            }
        }
        return false;
    }

    static boolean isConnectionState(String state) {
        if (state == null) return false;
        return state.startsWith("08") || state.equals("53300")
                || state.equals("57P01") || state.equals("57P02") || state.equals("57P03");
    }

    private static final long LOG_EVERY_NANOS = 10_000_000_000L;
    private static final AtomicLong lastLog = new AtomicLong(System.nanoTime() - LOG_EVERY_NANOS);

    /** At most one overload log line per 10 s: under overload every request would log otherwise. */
    public static boolean shouldLog() {
        long now = System.nanoTime();
        long last = lastLog.get();
        return now - last >= LOG_EVERY_NANOS && lastLog.compareAndSet(last, now);
    }

    /** Writes the 503 JSON body (filters, where no exception handler runs). */
    public static void write(HttpServletResponse res) throws IOException {
        if (res.isCommitted()) return;
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
        res.setContentType("application/json;charset=UTF-8");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + MESSAGE + "\",\"code\":\"" + ErrorCodes.SERVICE_UNAVAILABLE + "\"}");
    }
}
