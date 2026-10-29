package com.bilpark.backend.postgres;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.model.PaymentMethod;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.repository.ZoneRepository;
import com.bilpark.backend.service.ParkingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL row locks / unique constraints: only one of two concurrent devices may win. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentCheckoutPostgresTest {

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

    @Autowired ParkingService parkingService;
    @Autowired ParkSpotRepository spotRepository;
    @Autowired ParkingRecordRepository recordRepository;
    @Autowired UserRepository userRepository;
    @Autowired ZoneRepository zoneRepository;

    private User admin() {
        return userRepository.findByUsername("admin").orElseThrow();
    }

    private Zone zone() {
        return zoneRepository.findAll().get(0);
    }

    private void checkIn(String plate) {
        Zone z = zone();
        parkingService.checkInVehicle(plate, "SMALL", z.getStreet(), "LEFT", z.getId(), admin());
    }

    /** Runs both tasks at the same moment; returns each outcome (null = success, else the exception). */
    private List<Throwable> race(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Throwable>> fs = new ArrayList<>();
            for (Callable<?> task : List.of(a, b)) {
                fs.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        task.call();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            List<Throwable> out = new ArrayList<>();
            for (Future<Throwable> f : fs) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertOneWinnerOneConflict(List<Throwable> outcomes) {
        long ok = outcomes.stream().filter(t -> t == null).count();
        assertEquals(1, ok, "exactly one success, got " + outcomes);
        Throwable loser = outcomes.stream().filter(t -> t != null).findFirst().orElseThrow();
        assertInstanceOf(ApiException.class, loser, "loser: " + loser);
        assertEquals(HttpStatus.CONFLICT, ((ApiException) loser).getStatus());
    }

    @Test
    void concurrentCheckoutProducesSingleRecord() throws Exception {
        User actor = admin();
        for (int i = 0; i < 5; i++) {
            String plate = "34CO" + (1000 + i);
            checkIn(plate);
            List<Throwable> res = race(
                    () -> parkingService.checkOutVehicle(plate, PaymentMethod.CASH, actor, "dev-A"),
                    () -> parkingService.checkOutVehicle(plate, PaymentMethod.POS, actor, "dev-B"));
            assertOneWinnerOneConflict(res);
            assertEquals(1, recordRepository.findByLicensePlateContainingOrderByEntryTimeDesc(plate).size());
            assertTrue(spotRepository.findByCurrentPlate(plate).isEmpty());
        }
    }

    @Test
    void concurrentCheckoutAndRunawayProducesSingleRecord() throws Exception {
        User actor = admin();
        for (int i = 0; i < 5; i++) {
            String plate = "34RU" + (2000 + i);
            checkIn(plate);
            List<Throwable> res = race(
                    () -> parkingService.checkOutVehicle(plate, PaymentMethod.CASH, actor, "dev-A"),
                    () -> parkingService.markAsRunaway(plate, actor, "dev-B"));
            assertOneWinnerOneConflict(res);
            assertEquals(1, recordRepository.findByLicensePlateContainingOrderByEntryTimeDesc(plate).size());
            assertTrue(spotRepository.findByCurrentPlate(plate).isEmpty());
        }
    }

    @Test
    void concurrentDoubleCheckInKeepsSingleSpot() throws Exception {
        for (int i = 0; i < 5; i++) {
            String plate = "34CI" + (3000 + i);
            Zone z = zone();
            User actor = admin();
            List<Throwable> res = race(
                    () -> parkingService.checkInVehicle(plate, "SMALL", z.getStreet(), "LEFT", z.getId(), actor),
                    () -> parkingService.checkInVehicle(plate, "SMALL", z.getStreet(), "RIGHT", z.getId(), actor));
            assertOneWinnerOneConflict(res);
            assertEquals(1, spotRepository.findAll().stream()
                    .filter(s -> plate.equals(s.getCurrentPlate())).count());
        }
    }
}
