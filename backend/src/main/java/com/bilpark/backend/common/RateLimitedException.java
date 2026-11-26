package com.bilpark.backend.common;

import org.springframework.http.HttpStatus;

/** 429 RATE_LIMITED; GlobalExceptionHandler also sends Retry-After (seconds). */
public class RateLimitedException extends RetryLaterException {

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, message, retryAfterSeconds);
    }
}
