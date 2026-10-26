package com.bilpark.backend.retention;

import com.bilpark.backend.model.*;
import com.bilpark.backend.repository.*;
import com.bilpark.backend.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 5 / C6: retention job on H2. Uses its own rows; never asserts global counts. */
@SpringBootTest
@ActiveProfiles("test")
class RetentionServiceTest {

    @Autowired RetentionService retentionService;
    @Autowired ParkingRecordRepository recordRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ParkSpotRepository spotRepository;
    @Autowired UserRepository userRepository;
    @Autowired ZoneRepository zoneRepository;
    @Autowired SettingsService settingsService;

    private static String plate() {
        return "RT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private ParkingRecord record(String plate, LocalDateTime entry, LocalDateTime exit) {
        ParkingRecord r = new ParkingRecord(plate, StreetLocation.TEVFIK_BEY, null, "r", "n", entry);
        r.setExitTime(exit);
        r.setFee(20.0);
        r.setStatus(ParkingStatus.PAID);
        return recordRepository.save(r);
    }

    private Subscription sub(String plate, LocalDate end, SubscriptionStatus status) {
        Subscription s = new Subscription(plate, VehicleType.SMALL, end.minusMonths(1), "Ali Veli", "5551112233");
        s.setEndDate(end);
        s.setStatus(status);
        return subscriptionRepository.save(s);
    }

    private AuditLog audit(LocalDateTime at, String before) {
        AuditLog a = new AuditLog();
        a.setCreatedAt(at);
        a.setActorUsername("t_retention");
        a.setAction(AuditAction.VEHICLE_CHECK_OUT);
        a.setSummary("34ABC123 çıkış");
        a.setBeforeData(before);
        a.setAfterData("{\"plate\":\"34ABC123\"}");
        return auditLogRepository.save(a);
    }

    private Shift shift(User u, LocalDateTime start, boolean closed) {
        Shift s = new Shift(u, u.getAssignedZone(), "ret-" + UUID.randomUUID().toString().substring(0, 8), start);
        if (closed) s.setEndTime(start.plusHours(8));
        return shiftRepository.save(s);
    }

    private void assertRuleResult(LocalDate today) {
        LocalDateTime y6 = today.minusYears(6).atTime(10, 0);
        LocalDateTime y4 = today.minusYears(4).atTime(10, 0);
        LocalDateTime y2 = today.minusYears(2).atTime(10, 0);
        User officer = userRepository.findByUsername("tevfik").orElseThrow();

        ParkingRecord oldRec = record(plate(), y6.minusHours(1), y6);
        ParkingRecord oldNoExit = record(plate(), y6, null);
        ParkingRecord midRec = record(plate(), y4.minusHours(1), y4);
        String midPlate = midRec.getLicensePlate();
        String recentPlate = plate();
        ParkingRecord recentRec = record(recentPlate, today.atStartOfDay().minusHours(3), today.atStartOfDay().minusHours(2));

        Subscription subOld = sub(plate(), today.minusYears(6), SubscriptionStatus.EXPIRED);
        Subscription subMid = sub(plate(), today.minusYears(2), SubscriptionStatus.EXPIRED);
        Subscription subNew = sub(plate(), today.minusMonths(6), SubscriptionStatus.EXPIRED);
        Subscription subActive = sub(plate(), today.plusDays(10), SubscriptionStatus.ACTIVE);

        AuditLog oldAudit = audit(y6, "{\"plate\":\"06XYZ999\"}");
        AuditLog newAudit = audit(today.atStartOfDay().minusDays(1), "{\"plate\":\"06XYZ999\"}");

        Shift oldShift = shift(officer, y2, true);
        Shift oldOpenShift = shift(officer, y2, false);
        Shift newShift = shift(officer, today.minusMonths(3).atTime(8, 0), true);

        // an active spot is never touched
        ParkSpot spot = new ParkSpot();
        String spotPlate = plate();
        spot.setCurrentPlate(spotPlate);
        spot.setStreet(StreetLocation.TEVFIK_BEY);
        spot.setEntryTime(y6);
        spotRepository.save(spot);

        RetentionService.RetentionResult first = retentionService.run(today);
        assertEquals(today, first.today());
        assertTrue(first.recordPlatesAnonymized() >= 2);
        assertTrue(first.subscriptionPlatesAnonymized() >= 1);
        assertTrue(first.auditLogsAnonymized() >= 1);
        assertTrue(first.subscriberDataCleared() >= 2);
        assertTrue(first.shiftsDeleted() >= 1);

        // financial records: only the old ones lose the plate, the rows stay
        ParkingRecord r1 = recordRepository.findById(oldRec.getId()).orElseThrow();
        assertEquals("ANONIM", r1.getLicensePlate());
        assertTrue(r1.isPlateAnonymized());
        assertEquals(20.0, r1.getFee());
        assertEquals("ANONIM", recordRepository.findById(oldNoExit.getId()).orElseThrow().getLicensePlate());
        ParkingRecord r3 = recordRepository.findById(midRec.getId()).orElseThrow();
        assertEquals(midPlate, r3.getLicensePlate());
        assertFalse(r3.isPlateAnonymized());
        assertEquals(recentPlate, recordRepository.findById(recentRec.getId()).orElseThrow().getLicensePlate());

        // subscriptions: plate after 5 years, personal data after 1 year
        Subscription s1 = subscriptionRepository.findById(subOld.getId()).orElseThrow();
        assertEquals("ANONIM", s1.getLicensePlate());
        assertNull(s1.getOwnerName());
        assertNull(s1.getOwnerPhone());
        Subscription s2 = subscriptionRepository.findById(subMid.getId()).orElseThrow();
        assertEquals(subMid.getLicensePlate(), s2.getLicensePlate());
        assertNull(s2.getOwnerName());
        assertNull(s2.getOwnerPhone());
        Subscription s3 = subscriptionRepository.findById(subNew.getId()).orElseThrow();
        assertEquals("Ali Veli", s3.getOwnerName());
        assertEquals("5551112233", s3.getOwnerPhone());
        assertEquals("Ali Veli", subscriptionRepository.findById(subActive.getId()).orElseThrow().getOwnerName());

        // audit rows: old details cleared, recent kept, row itself kept
        AuditLog a1 = auditLogRepository.findById(oldAudit.getId()).orElseThrow();
        assertNull(a1.getBeforeData());
        assertNull(a1.getAfterData());
        assertEquals(RetentionService.AUDIT_ANON_SUMMARY, a1.getSummary());
        AuditLog a2 = auditLogRepository.findById(newAudit.getId()).orElseThrow();
        assertNotNull(a2.getBeforeData());
        assertEquals("34ABC123 çıkış", a2.getSummary());

        // shifts: only closed shifts older than a year are deleted
        assertTrue(shiftRepository.findById(oldShift.getId()).isEmpty());
        assertTrue(shiftRepository.findById(oldOpenShift.getId()).isPresent());
        assertTrue(shiftRepository.findById(newShift.getId()).isPresent());

        // active spots untouched
        assertTrue(spotRepository.findByCurrentPlate(spotPlate).isPresent());

        // bookkeeping + audit
        String last = settingsService.get(SettingsService.KEY_RETENTION_LAST_RUN).orElseThrow();
        assertTrue(last.contains("\"today\":\"" + today + "\""), last);
        assertTrue(auditLogRepository.findAll().stream().anyMatch(a ->
                a.getAction() == AuditAction.RETENTION_RUN && AuditServiceSystem.is(a)));

        // idempotent
        RetentionService.RetentionResult second = retentionService.run(today);
        assertEquals(0, second.total(), second.toString());
    }

    /** helper: system-written rows */
    private static final class AuditServiceSystem {
        static boolean is(AuditLog a) { return "system".equals(a.getActorUsername()); }
    }

    @Test
    void appliesRulesToOldRowsOnlyAndIsIdempotent() {
        assertRuleResult(LocalDate.now());
    }

    @Test
    void fixedPastDateOnlyAffectsRowsOlderThanItsCutoffs() {
        // 2020-03-01: cutoffs 2015-03-01 (5 years) and 2019-03-01 (1 year); rows of other tests are far newer
        assertRuleResult(LocalDate.of(2020, 3, 1));
    }
}
