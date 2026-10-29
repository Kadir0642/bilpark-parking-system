package com.bilpark.backend.postgres;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.PaymentMethod;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.service.ParkingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First start of the latest backend on the March 2026 live database: Flyway upgrades it on boot,
 * Hibernate validates the result, the seed users are created, and the old rows are usable.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MarchLiveDatabaseAppPostgresTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("legacy/march-2026-live-schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        // baseline-on-migrate / baseline-version come from application.properties, as in production
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired ParkingService parkingService;
    @Autowired ParkingRecordRepository recordRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private User user(String name) {
        return userRepository.findByUsername(name).orElseThrow();
    }

    @Test
    void oldRowsWorkAfterTheFirstStart() {
        assertEquals(List.of("BASELINE:0", "SQL:1", "SQL:2", "SQL:3", "SQL:4", "SQL:5", "SQL:6", "SQL:7", "SQL:8"),
                jdbc.queryForList("SELECT type || ':' || version FROM flyway_schema_history ORDER BY installed_rank",
                        String.class));
        // users did not exist in March: created from ADMIN_PASSWORD / OFFICER_PASSWORD on the first start
        assertEquals(List.of("admin:ADMIN", "tevfik:OFFICER"),
                jdbc.queryForList("SELECT username || ':' || role FROM users WHERE role <> 'DEVELOPER' ORDER BY username",
                        String.class));

        // the old runaway debt (70) of a vehicle that is on the street again is shown
        ParkSpot back = parkingService.getAllSpots().stream()
                .filter(s -> s.getCurrentPlate().equals("11HJ404")).findFirst().orElseThrow();
        assertNull(back.getZone());
        assertEquals(70.0, back.getAccumulatedDebt());

        // the Tevfik Bey officer closes an old Tevfik Bey vehicle (no zone: falls back to the street)
        User officer = user("tevfik");
        ParkingRecord closed = parkingService.checkOutVehicle("11 BK 456", PaymentMethod.CASH, officer, "prova-1");
        assertEquals(ParkingStatus.PAID, closed.getStatus());
        assertEquals("tevfik", closed.getCollectedByUsername());
        assertEquals("LARGE", closed.getVehicleType());

        // ...but not one on another street
        assertThrows(AccessDeniedException.class,
                () -> parkingService.checkOutVehicle("34XYZ789", PaymentMethod.CASH, officer, "prova-1"));

        // an old runaway vehicle comes back: its March debt (80) is shown at check-in
        Long zoneId = officer.getAssignedZone().getId();
        ParkSpot again = parkingService.checkInVehicle("11 MN 606", "LARGE", StreetLocation.TEVFIK_BEY, "LEFT",
                zoneId, officer);
        assertEquals(80.0, again.getAccumulatedDebt());

        // the admin marks an old Cumhuriyet vehicle as runaway (optimistic lock on version 0 works)
        ParkingRecord runaway = parkingService.markAsRunaway("11DE202", user("admin"), "prova-1");
        assertEquals(ParkingStatus.RUNAWAY, runaway.getStatus());
        assertFalse(runaway.isDebtPaid());

        // the March records are all still there
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM parking_records WHERE id <= 5", Long.class));
        assertEquals(List.of("06FG303", "11ABC123", "11HJ404", "11MN606", "16KL505"),
                jdbc.queryForList("SELECT license_plate FROM parking_records WHERE id <= 5 ORDER BY 1", String.class));
        assertEquals(7, recordRepository.count());
    }
}
