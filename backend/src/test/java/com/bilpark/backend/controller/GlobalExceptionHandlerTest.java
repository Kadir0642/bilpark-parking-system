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
}
