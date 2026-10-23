package com.bilpark.backend.service;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.PaymentMethod;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.Subscription;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.TariffStatus;
import com.bilpark.backend.sync.ChangePublisher;
import com.bilpark.backend.model.SubscriptionStatus;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.bilpark.backend.repository.SubscriptionRepository;
import com.bilpark.backend.repository.ZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class ParkingServiceTest {

    @Mock
    private ParkSpotRepository parkSpotRepository;

    @Mock
    private ParkingRecordRepository parkingRecordRepository;

    @Mock
    private ZoneRepository zoneRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private TariffService tariffService;

    @Mock
    private ChangePublisher changePublisher;

    // Wednesday 2026-10-21 14:00 Europe/Istanbul
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private final Clock clock = fixedClock(LocalDateTime.of(2026, 10, 21, 14, 0));

    private ParkingService parkingService;

    private static Clock fixedClock(LocalDateTime at) {
        return Clock.fixed(at.atZone(ISTANBUL).toInstant(), ISTANBUL);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Tariff t = new Tariff();
        t.setId(7L);
        t.setStatus(TariffStatus.ACTIVE);
        t.setSmallFirstHour(25.0);
        t.setSmallExtraHour(15.0);
        t.setLargeFirstHour(50.0);
        t.setLargeExtraHour(30.0);
        t.setFreeMinutes(5);
        t.setPaidStart(LocalTime.of(8, 0));
        t.setPaidEnd(LocalTime.of(22, 0));
        when(tariffService.current()).thenReturn(t);
        parkingService = new ParkingService(parkSpotRepository, parkingRecordRepository, zoneRepository,
                subscriptionRepository, tariffService, changePublisher, clock);
    }

    private ParkSpot spot(String plate, VehicleType type, Zone zone, int minutesAgo) {
        ParkSpot s = new ParkSpot(plate, StreetLocation.CUMHURIYET, type, "Merkez", "Bilecik", "RIGHT", zone);
        s.setEntryTime(LocalDateTime.now(clock).minusMinutes(minutesAgo));
        return s;
    }

    private User admin() {
        User u = new User("admin", "x", Role.ADMIN, null);
        u.setId(1L);
        return u;
    }

    private ApiException thrown(Runnable r) {
        return assertThrows(ApiException.class, r::run);
    }

    @Test
    void testCheckInVehicle_Success() {
        when(parkSpotRepository.findByPlateForUpdate("34ABC123")).thenReturn(Optional.empty());
        when(parkSpotRepository.saveAndFlush(any(ParkSpot.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkSpot spot = parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null);

        assertEquals("34ABC123", spot.getCurrentPlate());
        assertEquals(VehicleType.SMALL, spot.getCurrentType());
        assertEquals(StreetLocation.TEVFIK_BEY, spot.getStreet());
        assertEquals("LEFT", spot.getSide());
        verify(parkSpotRepository, times(1)).saveAndFlush(any(ParkSpot.class));
    }

    @Test
    void testCheckIn_NormalizesPlate() {
        when(parkSpotRepository.findByPlateForUpdate("34ABC123")).thenReturn(Optional.empty());
        when(parkSpotRepository.saveAndFlush(any(ParkSpot.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkSpot spot = parkingService.checkInVehicle("34 abc-123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null);

        assertEquals("34ABC123", spot.getCurrentPlate());
        verify(parkSpotRepository).findByPlateForUpdate("34ABC123");
    }

    @Test
    void testCheckIn_InvalidPlate_400() {
        ApiException ex = thrown(() ->
                parkingService.checkInVehicle("XYZ", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(ErrorCodes.INVALID_PLATE, ex.getCode());
    }

    @Test
    void testCheckInVehicle_FailIfAlreadyParked() {
        Zone z = zone(3, StreetLocation.TEVFIK_BEY, 3);
        ParkSpot existing = new ParkSpot("34ABC123", StreetLocation.TEVFIK_BEY, VehicleType.SMALL, "Merkez", "Bilecik", "LEFT", z);
        when(parkSpotRepository.findByPlateForUpdate("34ABC123")).thenReturn(Optional.of(existing));

        ApiException ex = thrown(() ->
                parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(ErrorCodes.ALREADY_PARKED, ex.getCode());
        assertTrue(ex.getMessage().contains("34ABC123"));
        assertTrue(ex.getMessage().contains("zaten sistemde aktif"));
        assertTrue(ex.getMessage().contains("Z3"));
        verify(parkSpotRepository, never()).saveAndFlush(any(ParkSpot.class));
    }

    @Test
    void testCheckIn_DataIntegrityViolation_409() {
        when(parkSpotRepository.findByPlateForUpdate("34ABC123")).thenReturn(Optional.empty());
        when(parkSpotRepository.saveAndFlush(any(ParkSpot.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        ApiException ex = thrown(() ->
                parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(ErrorCodes.ALREADY_PARKED, ex.getCode());
        assertFalse(ex.getMessage().contains("constraint"));
    }

    @Test
    void testCheckIn_SetsDebtAndSubscriptionFlags() {
        ParkingRecord run = new ParkingRecord();
        run.setFee(40.0);
        run.setDebtPaid(false);
        when(parkSpotRepository.findByPlateForUpdate("34ABC123")).thenReturn(Optional.empty());
        when(parkingRecordRepository.findByLicensePlateAndStatus("34ABC123", ParkingStatus.RUNAWAY)).thenReturn(List.of(run));
        when(subscriptionRepository.findFirstByLicensePlateAndStatusOrderByEndDateDesc("34ABC123", SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeSub("34ABC123")));
        when(parkSpotRepository.saveAndFlush(any(ParkSpot.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkSpot spot = parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null);

        assertEquals(40.0, spot.getAccumulatedDebt());
        assertTrue(spot.getHasSubscription());
    }

    private Subscription activeSub(String plate) {
        return new Subscription(plate, VehicleType.SMALL, LocalDate.now(), "T", "5");
    }

    @Test
    void testCheckOutVehicle_Success() {
        String plate = "34XYZ789";
        // 1.5 saat: 50 (base) + 30 (extra) = 80
        ParkSpot spot = spot(plate, VehicleType.LARGE, null, 90);
        when(parkSpotRepository.findByPlateForUpdate(plate)).thenReturn(Optional.of(spot));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkingRecord record = parkingService.checkOutVehicle(plate);

        assertEquals(plate, record.getLicensePlate());
        assertEquals(ParkingStatus.PAID, record.getStatus());
        assertEquals(VehicleType.LARGE, record.getVehicleType());
        assertEquals(80.0, record.getFee());
        assertEquals(PaymentMethod.CASH, record.getPaymentMethod());
        verify(parkSpotRepository, times(1)).delete(spot);
        verify(parkSpotRepository).flush();
    }

    @Test
    void testCheckOut_PosRecordsMethodCollectorAndDevice() {
        ParkSpot spot = spot("34XYZ789", VehicleType.SMALL, null, 30);
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);
        User actor = admin();

        ParkingRecord r = parkingService.checkOutVehicle("34 xyz 789", PaymentMethod.POS, actor, "dev-1");

        assertEquals(PaymentMethod.POS, r.getPaymentMethod());
        assertEquals(25.0, r.getFee());
        assertSame(actor, r.getCollectedBy());
        assertEquals("admin", r.getCollectedByUsername());
        assertEquals("dev-1", r.getDeviceId());
    }

    @Test
    void testCheckOut_ActiveSubscriptionForcesSubscriptionAndZeroFee() {
        ParkSpot spot = spot("34XYZ789", VehicleType.SMALL, null, 120);
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));
        when(subscriptionRepository.findFirstByLicensePlateAndStatusOrderByEndDateDesc("34XYZ789", SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeSub("34XYZ789")));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkingRecord r = parkingService.checkOutVehicle("34XYZ789", PaymentMethod.CASH, admin(), "d");

        assertEquals(PaymentMethod.SUBSCRIPTION, r.getPaymentMethod());
        assertEquals(0.0, r.getFee());
    }

    @Test
    void testCheckOut_SubscriptionWithoutSubscription_400() {
        ParkSpot spot = spot("34XYZ789", VehicleType.SMALL, null, 120);
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));

        ApiException ex = thrown(() ->
                parkingService.checkOutVehicle("34XYZ789", PaymentMethod.SUBSCRIPTION, admin(), "d"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(ErrorCodes.NO_SUBSCRIPTION, ex.getCode());
        verify(parkSpotRepository, never()).delete(any(ParkSpot.class));
    }

    @Test
    void testCheckOut_Online_400() {
        ApiException ex = thrown(() ->
                parkingService.checkOutVehicle("34XYZ789", PaymentMethod.ONLINE, admin(), "d"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(ErrorCodes.INVALID_PAYMENT_METHOD, ex.getCode());
    }

    @Test
    void testCheckOut_SpotMissingButRecentRecord_409() {
        ParkingRecord last = new ParkingRecord();
        last.setStatus(ParkingStatus.PAID);
        last.setExitTime(LocalDateTime.now(clock).minusMinutes(1));
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.empty());
        when(parkingRecordRepository.findTopByLicensePlateOrderByExitTimeDesc("34XYZ789")).thenReturn(Optional.of(last));

        ApiException ex = thrown(() -> parkingService.checkOutVehicle("34XYZ789", PaymentMethod.CASH, admin(), "d"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(ErrorCodes.ALREADY_PROCESSED, ex.getCode());
        assertTrue(ex.getMessage().contains("az önce başka bir işlemle kapatıldı"));
        assertTrue(ex.getMessage().contains("Ödendi"));
    }

    @Test
    void testCheckOut_SpotMissingOldRecord_404() {
        ParkingRecord last = new ParkingRecord();
        last.setStatus(ParkingStatus.PAID);
        last.setExitTime(LocalDateTime.now(clock).minusHours(3));
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.empty());
        when(parkingRecordRepository.findTopByLicensePlateOrderByExitTimeDesc("34XYZ789")).thenReturn(Optional.of(last));

        ApiException ex = thrown(() -> parkingService.checkOutVehicle("34XYZ789"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals(ErrorCodes.VEHICLE_NOT_FOUND, ex.getCode());
    }

    @Test
    void testCheckOutVehicle_FailIfNotFound() {
        String plate = "34NF1234";
        when(parkSpotRepository.findByPlateForUpdate(plate)).thenReturn(Optional.empty());
        when(parkingRecordRepository.findTopByLicensePlateOrderByExitTimeDesc(plate)).thenReturn(Optional.empty());

        ApiException ex = thrown(() -> parkingService.checkOutVehicle(plate));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals(ErrorCodes.VEHICLE_NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("bulunamadı"));
    }

    @Test
    void testRunaway_RecordsCollectorDeviceAndNoPaymentMethod() {
        ParkSpot spot = spot("34XYZ789", VehicleType.SMALL, null, 70);
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkingRecord r = parkingService.markAsRunaway("34XYZ789", admin(), "dev-9");

        assertEquals(ParkingStatus.RUNAWAY, r.getStatus());
        assertFalse(r.isDebtPaid());
        assertNull(r.getPaymentMethod());
        assertEquals("dev-9", r.getDeviceId());
        assertEquals("admin", r.getCollectedByUsername());
        assertEquals(40.0, r.getFee()); // 25 + 1 extra hour (15)
        verify(parkSpotRepository).delete(spot);
    }

    @Test
    void testRunaway_SpotMissingRecentRecord_409() {
        ParkingRecord last = new ParkingRecord();
        last.setStatus(ParkingStatus.RUNAWAY);
        last.setExitTime(LocalDateTime.now(clock).minusMinutes(2));
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.empty());
        when(parkingRecordRepository.findTopByLicensePlateOrderByExitTimeDesc("34XYZ789")).thenReturn(Optional.of(last));

        ApiException ex = thrown(() -> parkingService.markAsRunaway("34XYZ789", admin(), "d"));
        assertEquals(ErrorCodes.ALREADY_PROCESSED, ex.getCode());
        assertTrue(ex.getMessage().contains("Kaçak"));
    }

    @Test
    void testCheckOut_OvernightCarriesPaidMinutes() {
        // clock 09:00 next morning: yesterday 21:00-22:00 (60) + today 08:00-09:00 (60) = 120 paid minutes
        ParkingService svc = new ParkingService(parkSpotRepository, parkingRecordRepository, zoneRepository,
                subscriptionRepository, tariffService, changePublisher, fixedClock(LocalDateTime.of(2026, 10, 21, 9, 0)));
        ParkSpot s = new ParkSpot("34NGT003", StreetLocation.CUMHURIYET, VehicleType.SMALL, "Merkez", "Bilecik", "LEFT", null);
        s.setEntryTime(LocalDateTime.of(2026, 10, 20, 21, 0));
        when(parkSpotRepository.findByPlateForUpdate("34NGT003")).thenReturn(Optional.of(s));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkingRecord r = svc.checkOutVehicle("34NGT003");

        assertEquals(120, r.getPaidMinutes());
        assertEquals(40.0, r.getFee());
        assertEquals(7L, r.getTariffId());
        assertEquals(LocalDateTime.of(2026, 10, 21, 9, 0), r.getExitTime());
    }

    @Test
    void testCheckOut_NightMinutesAreFree() {
        // entry 07:00 (one free hour before the window), clock 14:00 => 360 paid minutes
        ParkSpot s = new ParkSpot("34NGT002", StreetLocation.CUMHURIYET, VehicleType.SMALL, "Merkez", "Bilecik", "LEFT", null);
        s.setEntryTime(LocalDateTime.of(2026, 10, 21, 7, 0));
        when(parkSpotRepository.findByPlateForUpdate("34NGT002")).thenReturn(Optional.of(s));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkingRecord r = parkingService.checkOutVehicle("34NGT002");

        assertEquals(360, r.getPaidMinutes());
        assertEquals(25.0 + 5 * 15.0, r.getFee());
    }

    @Test
    void testCheckOut_SubscriptionStoresTariffAndPaidMinutes() {
        ParkSpot s = spot("34XYZ789", VehicleType.SMALL, null, 120);
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(s));
        when(subscriptionRepository.findFirstByLicensePlateAndStatusOrderByEndDateDesc("34XYZ789", SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeSub("34XYZ789")));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkingRecord r = parkingService.checkOutVehicle("34XYZ789", PaymentMethod.CASH, admin(), "d");

        assertEquals(0.0, r.getFee());
        assertEquals(120, r.getPaidMinutes());
        assertEquals(7L, r.getTariffId());
    }

    @Test
    void testPublishesEvents() {
        when(parkSpotRepository.findByPlateForUpdate("34ABC123")).thenReturn(Optional.empty());
        when(parkSpotRepository.saveAndFlush(any(ParkSpot.class))).thenAnswer(i -> i.getArguments()[0]);
        ParkSpot in = parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null);
        assertEquals(LocalDateTime.now(clock), in.getEntryTime());
        verify(changePublisher).zoneEvent(eq(SyncEventType.VEHICLE_ENTERED), any(), eq(StreetLocation.TEVFIK_BEY), argThat(m ->
                "34ABC123".equals(m.get("plate")) && "SMALL".equals(m.get("vehicleType"))
                        && "TEVFIK_BEY".equals(m.get("street")) && Boolean.FALSE.equals(m.get("hasSubscription"))));

        ParkSpot spot = spot("34XYZ789", VehicleType.SMALL, null, 70);
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);
        parkingService.checkOutVehicle("34XYZ789", PaymentMethod.POS, admin(), "d");
        verify(changePublisher).zoneEvent(eq(SyncEventType.VEHICLE_EXITED), any(), any(), argThat(m ->
                "34XYZ789".equals(m.get("plate")) && Double.valueOf(40.0).equals(m.get("fee"))
                        && "POS".equals(m.get("paymentMethod"))));

        ParkSpot spot2 = spot("34RUN001", VehicleType.SMALL, null, 70);
        when(parkSpotRepository.findByPlateForUpdate("34RUN001")).thenReturn(Optional.of(spot2));
        parkingService.markAsRunaway("34RUN001", admin(), "d");
        verify(changePublisher).zoneEvent(eq(SyncEventType.VEHICLE_RUNAWAY), any(), any(), argThat(m ->
                "34RUN001".equals(m.get("plate")) && Double.valueOf(40.0).equals(m.get("debt"))));
    }

    @Test
    void testRunaway_StoresTariffAndPaidMinutes() {
        ParkSpot spot = spot("34XYZ789", VehicleType.SMALL, null, 70);
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkingService.RunawayResult res = parkingService.markAsRunawayDetailed("34XYZ789", admin(), "d");

        assertEquals(70, res.record().getPaidMinutes());
        assertEquals(7L, res.record().getTariffId());
        assertEquals(1, res.segments().size());
    }

    @Test
    void testDebtContainsSegmentsAndKeepsOldKeys() {
        ParkSpot s = spot("34ABC123", VehicleType.SMALL, null, 90);
        when(parkSpotRepository.findByCurrentPlate("34ABC123")).thenReturn(Optional.of(s));

        Map<String, Object> d = parkingService.getDebtByPlate("34 abc 123");

        for (String k : List.of("streetName", "plate", "entryTime", "durationMinutes", "fee", "hasSubscription",
                "paidMinutes", "freeMinutes", "tariffId", "segments")) {
            assertTrue(d.containsKey(k), k);
        }
        assertEquals(40.0, d.get("fee"));
        assertEquals(90L, d.get("durationMinutes"));
        assertEquals(1, ((List<?>) d.get("segments")).size());
    }

    @Test
    void testFeePreview_OfficerOtherZone_Denied() {
        Zone other = zone(2, StreetLocation.TEVFIK_BEY, 2);
        ParkSpot s = spot("34ABC123", VehicleType.SMALL, other, 30);
        when(parkSpotRepository.findByCurrentPlate("34ABC123")).thenReturn(Optional.of(s));
        User off = officer(zone(1, StreetLocation.TEVFIK_BEY, 1));

        assertThrows(AccessDeniedException.class, () -> parkingService.getFeePreview("34ABC123", off));
        Map<String, Object> ok = parkingService.getFeePreview("34ABC123", admin());
        assertEquals("SMALL", ok.get("vehicleType"));
        assertEquals(2L, ok.get("zoneId"));
    }

    // --- BI ---

    @Test
    void testIncomeByVehicleType_MapsNullAndLarge() {
        when(parkingRecordRepository.sumPaidIncomeByVehicleType()).thenReturn(List.of(
                new Object[]{null, 10.0},
                new Object[]{VehicleType.SMALL, 15.0},
                new Object[]{VehicleType.LARGE, 80.0}));

        Map<String, Double> m = parkingService.calculateIncomeByVehicleType();

        assertEquals(25.0, m.get("Otomobil"));
        assertEquals(80.0, m.get("Kamyonet"));
    }

    @Test
    void testIncomeByVehicleType_EmptyHasBothKeys() {
        when(parkingRecordRepository.sumPaidIncomeByVehicleType()).thenReturn(List.of());
        Map<String, Double> m = parkingService.calculateIncomeByVehicleType();
        assertEquals(0.0, m.get("Otomobil"));
        assertEquals(0.0, m.get("Kamyonet"));
    }

    @Test
    void testStatusCounts() {
        when(parkingRecordRepository.countByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{ParkingStatus.PAID, 7L},
                new Object[]{ParkingStatus.RUNAWAY, 2L}));
        Map<String, Long> m = parkingService.getParkingStatusCounts();
        assertEquals(7L, m.get("Ödenen"));
        assertEquals(2L, m.get("Kaçan"));

        when(parkingRecordRepository.countByStatus()).thenReturn(List.of());
        m = parkingService.getParkingStatusCounts();
        assertEquals(0L, m.get("Ödenen"));
        assertEquals(0L, m.get("Kaçan"));
    }

    @Test
    void testSearchHistory_CleansInput() {
        parkingService.searchHistoryByPlate("34 ab");
        verify(parkingRecordRepository).findByLicensePlateContainingOrderByEntryTimeDesc("34AB");
    }

    // --- Officer zone restriction ---

    private Zone zone(long id, StreetLocation street, int no) {
        Zone z = new Zone(street, no, "Z" + id, "l", "r");
        z.setId(id);
        return z;
    }

    private User officer(Zone z) {
        return new User("off", "x", Role.OFFICER, z);
    }

    @Test
    void testOfficerCheckIn_WrongZone_Denied() {
        User off = officer(zone(1, StreetLocation.TEVFIK_BEY, 1));
        assertThrows(AccessDeniedException.class, () ->
                parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", 2L, off));
        verify(parkSpotRepository, never()).saveAndFlush(any(ParkSpot.class));
    }

    @Test
    void testOfficerCheckIn_WrongStreet_Denied() {
        User off = officer(zone(1, StreetLocation.TEVFIK_BEY, 1));
        assertThrows(AccessDeniedException.class, () ->
                parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.CUMHURIYET, "LEFT", 1L, off));
    }

    @Test
    void testOfficerCheckIn_NullZoneUsesAssigned() {
        Zone z = zone(1, StreetLocation.TEVFIK_BEY, 1);
        when(zoneRepository.findById(1L)).thenReturn(Optional.of(z));
        when(parkSpotRepository.findByPlateForUpdate("34ABC123")).thenReturn(Optional.empty());
        when(parkSpotRepository.saveAndFlush(any(ParkSpot.class))).thenAnswer(i -> i.getArguments()[0]);

        ParkSpot spot = parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null, officer(z));

        assertEquals(z, spot.getZone());
    }

    @Test
    void testOfficerWithoutZone_Denied() {
        User off = officer(null);
        assertThrows(AccessDeniedException.class, () ->
                parkingService.checkInVehicle("34ABC123", "SMALL", StreetLocation.TEVFIK_BEY, "LEFT", null, off));
    }

    @Test
    void testOfficerCheckOut_OtherZone_Denied() {
        Zone other = zone(2, StreetLocation.TEVFIK_BEY, 2);
        ParkSpot spot = new ParkSpot("34XYZ789", StreetLocation.TEVFIK_BEY, VehicleType.SMALL, "Merkez", "Bilecik", "LEFT", other);
        spot.setEntryTime(LocalDateTime.now(clock));
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));

        User off = officer(zone(1, StreetLocation.TEVFIK_BEY, 1));
        assertThrows(AccessDeniedException.class, () -> parkingService.checkOutVehicle("34XYZ789", off));
        assertThrows(AccessDeniedException.class, () -> parkingService.markAsRunaway("34XYZ789", off));
        verify(parkSpotRepository, never()).delete(any(ParkSpot.class));
    }

    @Test
    void testOfficerCheckOut_LegacySpotWithoutZone_ChecksStreet() {
        ParkSpot spot = new ParkSpot("34XYZ789", StreetLocation.CUMHURIYET, VehicleType.SMALL, "Merkez", "Bilecik", "LEFT", null);
        spot.setEntryTime(LocalDateTime.now(clock));
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));

        User off = officer(zone(1, StreetLocation.TEVFIK_BEY, 1));
        assertThrows(AccessDeniedException.class, () -> parkingService.checkOutVehicle("34XYZ789", off));
    }

    @Test
    void testAdmin_Unrestricted() {
        Zone other = zone(2, StreetLocation.TEVFIK_BEY, 2);
        ParkSpot spot = new ParkSpot("34XYZ789", StreetLocation.TEVFIK_BEY, VehicleType.SMALL, "Merkez", "Bilecik", "LEFT", other);
        spot.setEntryTime(LocalDateTime.now(clock));
        when(parkSpotRepository.findByPlateForUpdate("34XYZ789")).thenReturn(Optional.of(spot));
        when(parkingRecordRepository.save(any(ParkingRecord.class))).thenAnswer(i -> i.getArguments()[0]);

        assertNotNull(parkingService.checkOutVehicle("34XYZ789", admin()));
    }
}
