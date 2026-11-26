package com.bilpark.backend.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;

class OverloadTest {

    @Test
    void poolTimeoutAndConnectionFailuresAreUnavailable() {
        var hikari = new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out");
        assertTrue(Overload.isDatabaseUnavailable(hikari));
        assertTrue(Overload.isDatabaseUnavailable(new CannotCreateTransactionException("x", hikari)));
        assertTrue(Overload.isDatabaseUnavailable(new DataAccessResourceFailureException("x",
                new RuntimeException("wrapped", new SQLException("I/O error", "08006")))));
        assertTrue(Overload.isDatabaseUnavailable(new CannotGetJdbcConnectionException("x")));
        assertTrue(Overload.isDatabaseUnavailable(new SQLException("too many clients", "53300")));
        assertTrue(Overload.isDatabaseUnavailable(new SQLException("starting up", "57P03")));
    }

    @Test
    void otherDatabaseErrorsAreNot() {
        assertFalse(Overload.isDatabaseUnavailable(new DataIntegrityViolationException("dup",
                new SQLException("duplicate key", "23505"))));
        assertFalse(Overload.isDatabaseUnavailable(new SQLException("syntax", "42601")));
        assertFalse(Overload.isDatabaseUnavailable(new IllegalStateException("x")));
        assertFalse(Overload.isDatabaseUnavailable(null));
    }
}
