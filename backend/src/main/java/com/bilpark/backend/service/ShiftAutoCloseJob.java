package com.bilpark.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every night at 22:30 (Europe/Istanbul; working hours end at 22:00) closes shifts the officers
 * forgot to close. One query a day, so it is fine for a scale-to-zero database. Never throws.
 * Render may be asleep at 22:30 and miss the run; the next night's run closes the leftovers with the
 * same boundary (ShiftService.closingTimeFor), so worked hours are not inflated.
 */
@Component
public class ShiftAutoCloseJob {

    private static final Logger log = LoggerFactory.getLogger(ShiftAutoCloseJob.class);

    private final ShiftService shiftService;

    public ShiftAutoCloseJob(ShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @Scheduled(cron = "0 30 22 * * *", zone = "Europe/Istanbul")
    public void nightly() {
        try {
            int n = shiftService.closeForgottenShifts();
            log.info("Gece vardiya temizliği: {} açık vardiya kapatıldı", n);
        } catch (RuntimeException e) {
            log.error("Gece vardiya temizliği başarısız", e);
        }
    }
}
