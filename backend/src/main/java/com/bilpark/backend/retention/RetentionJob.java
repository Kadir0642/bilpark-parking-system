package com.bilpark.backend.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** Nightly trigger for RetentionService (separate bean so the service's @Transactional proxy applies). */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionService retentionService;
    private final Clock clock;

    public RetentionJob(RetentionService retentionService, Clock clock) {
        this.retentionService = retentionService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "Europe/Istanbul")
    public void nightly() {
        try {
            RetentionService.RetentionResult r = retentionService.run(LocalDate.now(clock));
            log.info("Retention run finished: {}", r);
        } catch (RuntimeException e) {
            log.error("Retention run failed", e);
        }
    }
}
