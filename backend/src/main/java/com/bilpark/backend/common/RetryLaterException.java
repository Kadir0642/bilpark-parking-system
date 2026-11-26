package com.bilpark.backend.common;

import org.springframework.http.HttpStatus;

/** ApiException the client may retry: GlobalExceptionHandler also sends Retry-After (seconds). */
public class RetryLaterException extends ApiException {

    private final long retryAfterSeconds;

    public RetryLaterException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(status, code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }

    /** 503 REPORT_BUSY: another report is being prepared. */
    public static RetryLaterException reportBusy(long retryAfterSeconds) {
        return new RetryLaterException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.REPORT_BUSY,
                "Şu anda başka bir rapor hazırlanıyor. Lütfen birkaç saniye sonra tekrar deneyin.", retryAfterSeconds);
    }
}
