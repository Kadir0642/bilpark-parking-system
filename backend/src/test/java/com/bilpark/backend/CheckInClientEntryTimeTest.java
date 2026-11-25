package com.bilpark.backend;

import com.bilpark.backend.config.ClockConfig;
import com.bilpark.backend.fee.FeeBreakdown;
import com.bilpark.backend.fee.FeeEngine;
import com.bilpark.backend.fee.TariffRules;
import com.bilpark.backend.model.AuditLog;
import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.repository.AuditLogRepository;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.bilpark.backend.repository.TariffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/parking/check-in clientEntryTime: çevrimdışı kuyruktaki girişin memur cihazındaki saati. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckInClientEntryTimeTest {

    @Autowired private MockMvc mvc;
    @Autowired private ParkSpotRepository spots;
    @Autowired private ParkingRecordRepository records;
    @Autowired private AuditLogRepository audits;
    @Autowired private TariffRepository tariffs;

    private String admin() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException(body);
        return "Bearer " + m.group(1);
    }

    private ResultActions checkIn(String auth, String plate, String reqId, String clientTime) throws Exception {
        var req = post("/api/parking/check-in").header("Authorization", auth)
                .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1");
        if (reqId != null) req.param("checkInRequestId", reqId);
        if (clientTime != null) req.param("clientEntryTime", clientTime);
        return mvc.perform(req);
    }

    private static String utc(Instant i) {
        return i.truncatedTo(ChronoUnit.SECONDS).toString(); // 2026-10-29T08:15:00Z
    }

    private static LocalDateTime local(Instant i) {
        return LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.SECONDS), ClockConfig.ZONE);
    }

    private static LocalDateTime serverNow() {
        return LocalDateTime.now(ClockConfig.ZONE);
    }

    private ParkSpot spot(String plate) {
        return spots.findAll().stream().filter(s -> plate.equals(s.getCurrentPlate())).findFirst().orElseThrow();
    }

    private String checkInAudit(ParkSpot s) {
        return audits.findAll().stream()
                .filter(a -> String.valueOf(s.getId()).equals(a.getEntityId()) && "ParkSpot".equals(a.getEntityType())
                        && a.getAction().name().equals("VEHICLE_CHECK_IN"))
                .map(AuditLog::getAfterData).findFirst().orElseThrow();
    }

    private static void assertServerTime(LocalDateTime before, LocalDateTime actual, LocalDateTime after) {
        assertFalse(actual.isBefore(before), actual + " < " + before);
        assertFalse(actual.isAfter(after), actual + " > " + after);
    }

    @Test
    void clientTimeInRange_isEntryTime_andFeeCountsFromIt() throws Exception {
        String adm = admin();
        Instant officerTapped = Instant.now().minus(Duration.ofHours(3));
        checkIn(adm, "34CET001", "req-cet-0001", utc(officerTapped)).andExpect(status().isOk());

        ParkSpot s = spot("34CET001");
        assertEquals(local(officerTapped), s.getEntryTime());
        String after = checkInAudit(s);
        assertTrue(after.contains("\"entryTimeSource\":\"CLIENT\""), after);
        assertTrue(after.contains("\"clientEntryTime\":\"" + utc(officerTapped) + "\""), after);

        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", "34CET001"))
                .andExpect(status().isOk());
        List<ParkingRecord> paid = records.findByLicensePlateAndStatus("34CET001", ParkingStatus.PAID);
        assertEquals(1, paid.size());
        ParkingRecord r = paid.get(0);
        assertEquals(local(officerTapped), r.getEntryTime());
        assertTrue(Duration.between(r.getEntryTime(), r.getExitTime()).toMinutes() >= 180);

        // Ücret motoru değişmedi: kayıttaki ücret, istemci giriş saatinden çıkışa kadar hesaplanan ücrettir
        Tariff t = tariffs.findById(r.getTariffId()).orElseThrow();
        FeeBreakdown expected = FeeEngine.calculate(TariffRules.from(t), r.getVehicleType(), r.getEntryTime(), r.getExitTime());
        assertEquals(expected.fee(), r.getFee(), 0.001);
        assertEquals((int) expected.paidMinutes(), r.getPaidMinutes());
    }

    @Test
    void clientTimeWithOffset_isConvertedToServerZone() throws Exception {
        String adm = admin();
        Instant tapped = Instant.now().minus(Duration.ofMinutes(47));
        String withOffset = tapped.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.ofHours(3)).toString();
        checkIn(adm, "34CET002", "req-cet-0002", withOffset).andExpect(status().isOk());
        assertEquals(local(tapped), spot("34CET002").getEntryTime());
    }

    @Test
    void clientTimeFarInFuture_usesServerTime() throws Exception {
        String adm = admin();
        LocalDateTime before = serverNow();
        checkIn(adm, "34CET003", "req-cet-0003", utc(Instant.now().plus(Duration.ofHours(1)))).andExpect(status().isOk());
        ParkSpot s = spot("34CET003");
        assertServerTime(before, s.getEntryTime(), serverNow());
        assertTrue(checkInAudit(s).contains("\"entryTimeSource\":\"SERVER\""));
    }

    @Test
    void smallFutureSkew_isClampedToServerTime() throws Exception {
        String adm = admin();
        LocalDateTime before = serverNow();
        checkIn(adm, "34CET004", "req-cet-0004", utc(Instant.now().plus(Duration.ofMinutes(2)))).andExpect(status().isOk());
        ParkSpot s = spot("34CET004");
        assertServerTime(before, s.getEntryTime(), serverNow());
        assertTrue(checkInAudit(s).contains("\"entryTimeSource\":\"CLIENT\""));
    }

    @Test
    void clientTimeOlderThan24h_usesServerTime() throws Exception {
        String adm = admin();
        LocalDateTime before = serverNow();
        checkIn(adm, "34CET005", "req-cet-0005", utc(Instant.now().minus(Duration.ofHours(25)))).andExpect(status().isOk());
        ParkSpot s = spot("34CET005");
        assertServerTime(before, s.getEntryTime(), serverNow());
        assertTrue(checkInAudit(s).contains("\"entryTimeSource\":\"SERVER\""));
    }

    @Test
    void unparseableClientTime_doesNotRejectEntry_usesServerTime() throws Exception {
        String adm = admin();
        LocalDateTime before = serverNow();
        checkIn(adm, "34CET006", "req-cet-0006", "dün öğlen").andExpect(status().isOk());
        assertServerTime(before, spot("34CET006").getEntryTime(), serverNow());
    }

    @Test
    void fieldMissing_behaviourUnchanged() throws Exception {
        String adm = admin();
        LocalDateTime before = serverNow();
        checkIn(adm, "34CET007", null, null).andExpect(status().isOk());
        ParkSpot s = spot("34CET007");
        assertServerTime(before, s.getEntryTime(), serverNow());
        String after = checkInAudit(s);
        assertTrue(after.contains("\"entryTimeSource\":\"SERVER\""), after);
        assertFalse(after.contains("clientEntryTime"), after);
    }

    @Test
    void idempotentReplay_keepsFirstEntryTime() throws Exception {
        String adm = admin();
        Instant first = Instant.now().minus(Duration.ofMinutes(90));
        checkIn(adm, "34CET008", "req-cet-0008", utc(first)).andExpect(status().isOk());
        long id = spot("34CET008").getId();
        // Yanıt kaybolmuş, kuyruk aynı kimlikle tekrar gönderiyor (saat değişmiş olsa bile ilk kayıt geçerli)
        checkIn(adm, "34CET008", "req-cet-0008", utc(Instant.now().minus(Duration.ofMinutes(10)))).andExpect(status().isOk());
        ParkSpot s = spot("34CET008");
        assertEquals(id, s.getId());
        assertEquals(local(first), s.getEntryTime());
        assertEquals(1, spots.findAll().stream().filter(x -> "34CET008".equals(x.getCurrentPlate())).count());
    }
}
