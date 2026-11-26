package com.bilpark.backend.report;

import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.common.RetryLaterException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReportGateTest {

    @Test
    void secondReportWhileOneRunsGets503ReportBusy() throws Exception {
        ReportGate gate = new ReportGate(1, 50, 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = new Thread(() -> gate.run(() -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            return 1;
        }));
        first.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        RetryLaterException e = assertThrows(RetryLaterException.class, () -> gate.run(() -> 2));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(ErrorCodes.REPORT_BUSY, e.getCode());
        assertEquals(10, e.getRetryAfterSeconds());

        release.countDown();
        first.join(5000);
        assertEquals(3, gate.run(() -> 3)); // permit released
    }

    @Test
    void permitIsReleasedWhenTheReportFails() {
        ReportGate gate = new ReportGate(1, 0, 10);
        assertThrows(IllegalStateException.class, () -> gate.run(() -> { throw new IllegalStateException("x"); }));
        assertEquals("ok", gate.run(() -> "ok"));
    }
}
