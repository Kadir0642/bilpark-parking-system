package com.bilpark.backend.license;

import com.bilpark.backend.model.LicenseInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class LicenseStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private static LicenseInfo license(LocalDate expiresOn, int grace) {
        LicenseInfo l = new LicenseInfo();
        l.setLicensee("Test");
        l.setExpiresOn(expiresOn);
        l.setGraceDays(grace);
        return l;
    }

    private static LicenseService.LicenseStatus daysLeft(int days) {
        return LicenseService.statusOf(license(TODAY.plusDays(days), 15), TODAY);
    }

    @Test
    void warningLevelsWhileActive() {
        assertNull(daysLeft(31).warningLevel());
        assertEquals(30, daysLeft(30).warningLevel());
        assertEquals(30, daysLeft(8).warningLevel());
        assertEquals(7, daysLeft(7).warningLevel());
        assertEquals(7, daysLeft(2).warningLevel());
        assertEquals(1, daysLeft(1).warningLevel());
        assertEquals(1, daysLeft(0).warningLevel());
        for (int d : new int[]{31, 30, 8, 7, 2, 1, 0}) {
            LicenseService.LicenseStatus s = daysLeft(d);
            assertEquals("ACTIVE", s.state());
            assertFalse(s.readOnly());
            assertEquals(d, s.daysLeft());
        }
    }

    @Test
    void graceAfterExpiry() {
        LicenseService.LicenseStatus s = LicenseService.statusOf(license(TODAY.minusDays(1), 15), TODAY);
        assertEquals("GRACE", s.state());
        assertEquals(0, s.warningLevel());
        assertFalse(s.readOnly());
        assertEquals(14, s.graceDaysLeft());
        assertEquals(TODAY.minusDays(1).plusDays(15), s.graceEndsOn());

        // last day of the grace period still writable
        LicenseService.LicenseStatus last = LicenseService.statusOf(license(TODAY.minusDays(15), 15), TODAY);
        assertEquals("GRACE", last.state());
        assertFalse(last.readOnly());
    }

    @Test
    void readOnlyAfterGrace() {
        LicenseService.LicenseStatus s = LicenseService.statusOf(license(TODAY.minusDays(16), 15), TODAY);
        assertEquals("READ_ONLY", s.state());
        assertTrue(s.readOnly());
        assertEquals(0, s.warningLevel());
        assertEquals(LicenseService.READ_ONLY_MESSAGE, s.message());
    }

    @Test
    void zeroGraceGoesReadOnlyTheDayAfterExpiry() {
        assertEquals("ACTIVE", LicenseService.statusOf(license(TODAY, 0), TODAY).state());
        assertEquals("READ_ONLY", LicenseService.statusOf(license(TODAY.minusDays(1), 0), TODAY).state());
    }
}
