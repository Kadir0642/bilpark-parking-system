package com.bilpark.backend.postgres;

import com.bilpark.backend.model.*;
import com.bilpark.backend.repository.*;
import com.bilpark.backend.retention.RetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** The retention JPQL bulk updates / deletes must also work on real PostgreSQL (Flyway V1-V4, ddl-auto=validate). */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RetentionPostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired RetentionService retentionService;
    @Autowired ParkingRecordRepository recordRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.bilpark.backend.settings.SettingsService settingsService;

    @Test
    void retentionRunWorksOnPostgres() {
        LocalDate today = LocalDate.of(2031, 6, 15);

        ParkingRecord old = new ParkingRecord("34OLD111", StreetLocation.TEVFIK_BEY, null, "r", "n",
                today.minusYears(6).atTime(9, 0));
        old.setExitTime(today.minusYears(6).atTime(10, 0));
        old.setFee(30.0);
        old.setStatus(ParkingStatus.PAID);
        old = recordRepository.save(old);
        ParkingRecord fresh = new ParkingRecord("34NEW222", StreetLocation.TEVFIK_BEY, null, "r", "n",
                today.minusDays(2).atTime(9, 0));
        fresh.setExitTime(today.minusDays(2).atTime(10, 0));
        fresh.setFee(30.0);
        fresh.setStatus(ParkingStatus.PAID);
        fresh = recordRepository.save(fresh);

        Subscription sub = new Subscription("34SUB333", "SMALL", today.minusYears(6).minusMonths(1), "Ayşe", "555");
        sub.setEndDate(today.minusYears(6));
        sub.setStatus(SubscriptionStatus.EXPIRED);
        sub = subscriptionRepository.save(sub);

        AuditLog a = new AuditLog();
        a.setCreatedAt(today.minusYears(6).atTime(12, 0));
        a.setActorUsername("t_pg");
        a.setAction(AuditAction.VEHICLE_CHECK_IN);
        a.setSummary("34OLD111 giriş");
        a.setBeforeData(null);
        a.setAfterData("{\"plate\":\"34OLD111\"}");
        a = auditLogRepository.save(a);

        User officer = userRepository.findByUsername("tevfik").orElseThrow();
        Shift oldShift = new Shift(officer, officer.getAssignedZone(), "pg-dev", today.minusYears(2).atTime(8, 0));
        oldShift.setEndTime(today.minusYears(2).atTime(17, 0));
        oldShift = shiftRepository.save(oldShift);

        RetentionService.RetentionResult r = retentionService.run(today);
        assertEquals(1, r.recordPlatesAnonymized());
        assertEquals(1, r.subscriptionPlatesAnonymized());
        assertEquals(1, r.auditLogsAnonymized());
        assertEquals(1, r.subscriberDataCleared());
        assertEquals(1, r.shiftsDeleted());

        assertEquals("ANONIM", recordRepository.findById(old.getId()).orElseThrow().getLicensePlate());
        assertTrue(recordRepository.findById(old.getId()).orElseThrow().isPlateAnonymized());
        assertEquals("34NEW222", recordRepository.findById(fresh.getId()).orElseThrow().getLicensePlate());
        Subscription s = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertEquals("ANONIM", s.getLicensePlate());
        assertNull(s.getOwnerName());
        assertNull(auditLogRepository.findById(a.getId()).orElseThrow().getAfterData());
        assertTrue(shiftRepository.findById(oldShift.getId()).isEmpty());
        assertTrue(settingsService.get("retention.last-run").orElseThrow().contains("2031-06-15"));

        assertEquals(0, retentionService.run(today).total());
    }
}
