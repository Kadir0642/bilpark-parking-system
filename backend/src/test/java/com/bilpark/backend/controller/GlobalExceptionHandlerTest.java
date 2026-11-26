package com.bilpark.backend.controller;

import com.bilpark.backend.audit.ErrorLogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GlobalExceptionHandlerTest {

    private final ErrorLogService errorLog = mock(ErrorLogService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(errorLog);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void asyncRequestNotUsableIsNotRecorded() {
        var result = handler.handleIo(new AsyncRequestNotUsableException(
                "ServletOutputStream failed to flush: java.io.IOException: Broken pipe"), response);
        assertThat(result).isNull();
        verify(errorLog, never()).record(any(), anyString());
    }

    @Test
    void brokenPipeIoExceptionIsNotRecorded() {
        assertThat(handler.handleIo(new IOException("Broken pipe"), response)).isNull();
        assertThat(handler.handleIo(new IOException("Connection reset by peer"), response)).isNull();
        assertThat(handler.handleIo(new IOException("wrapper", new IOException("Broken pipe")), response)).isNull();
        verify(errorLog, never()).record(any(), anyString());
    }

    @Test
    void realIoExceptionIsStillRecorded() {
        var ex = new IOException("Disk read failed");
        var result = handler.handleIo(ex, response);
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(errorLog).record(ex, "INTERNAL_ERROR");
    }

    @Test
    void genericRuntimeFailureIsStillRecorded() {
        var ex = new IllegalStateException("boom");
        var result = handler.handleGeneric(ex);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(errorLog).record(ex, "INTERNAL_ERROR");
    }

    // ---- overload: no connection in time -> 503 + Retry-After, not 500 / 400, not stored ----

    private static final java.sql.SQLTransientConnectionException POOL_TIMEOUT =
            new java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms.");

    private void assertUnavailable(org.springframework.http.ResponseEntity<java.util.Map<String, String>> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(r.getBody()).containsEntry("code", "SERVICE_UNAVAILABLE");
    }

    @Test
    void poolTimeoutOutsideDataAccessHierarchyIs503() {
        // JpaTransactionManager: CannotCreateTransactionException is not a DataAccessException (was 400 via RuntimeException)
        assertUnavailable(handler.handleRuntime(
                new org.springframework.transaction.CannotCreateTransactionException("Could not open JPA EntityManager", POOL_TIMEOUT)));
        verify(errorLog, never()).record(any(), anyString());
    }

    @Test
    void poolTimeoutInsideDataAccessIs503() {
        assertUnavailable(handler.handleDataAccess(
                new org.springframework.dao.DataAccessResourceFailureException("Unable to acquire JDBC Connection", POOL_TIMEOUT)));
        assertUnavailable(handler.handleGeneric(new Exception("wrapped", POOL_TIMEOUT)));
        verify(errorLog, never()).record(any(), anyString());
    }

    @Test
    void reportBusyIs503WithRetryAfter() {
        var r = handler.handleApi(com.bilpark.backend.common.RetryLaterException.reportBusy(10));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("10");
        assertThat(r.getBody()).containsEntry("code", "REPORT_BUSY");
    }

    @Test
    void otherDatabaseErrorStays500AndRecorded() {
        var ex = new org.springframework.dao.InvalidDataAccessResourceUsageException("bad sql");
        assertThat(handler.handleDataAccess(ex).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(errorLog).record(ex, "INTERNAL_ERROR");
    }
}
