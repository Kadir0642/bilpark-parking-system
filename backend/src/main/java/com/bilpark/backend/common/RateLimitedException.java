package com.bilpark.backend.common;

import org.springframework.http.HttpStatus;

/** 429 RATE_LIMITED; GlobalExceptionHandler also sends Retry-After (seconds). */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
