package com.bilpark.backend.report;

import com.bilpark.backend.common.RetryLaterException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * At most {@code max-concurrent} (default 1) reports are computed at a time. A report reads a whole period into
 * memory (up to 366 days / 100k rows) and, on a 0.1 CPU / 512 MB host, two of them side by side exhausted the
 * heap (load test). A request that cannot start within {@code wait-ms} gets 503 REPORT_BUSY + Retry-After.
 */
@Component
public class ReportGate {

    private final Semaphore permits;
    private final long waitMs;
    private final long retryAfterSeconds;

    public ReportGate(@Value("${bilpark.reports.max-concurrent:1}") int maxConcurrent,
                      @Value("${bilpark.reports.wait-ms:5000}") long waitMs,
                      @Value("${bilpark.reports.retry-after-seconds:10}") long retryAfterSeconds) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
        this.waitMs = Math.max(0, waitMs);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public <T> T run(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) throw RetryLaterException.reportBusy(retryAfterSeconds);
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
