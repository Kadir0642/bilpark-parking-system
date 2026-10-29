package com.bilpark.backend.developer;

import com.bilpark.backend.license.LicenseService;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.AuditLog;
import com.bilpark.backend.model.ErrorLog;
import com.bilpark.backend.model.LicenseInfo;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.AuditLogRepository;
import com.bilpark.backend.repository.ErrorLogRepository;
import com.bilpark.backend.repository.LicenseInfoRepository;
import com.bilpark.backend.repository.ShiftRepository;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.repository.ZoneRepository;
import com.bilpark.backend.service.TariffService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/api-gelistirici-izleme.md: developer audit (masked), activity, health extras, access rules. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeveloperMonitoringIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditLogRepository audits;
    @Autowired ErrorLogRepository errorLogs;
    @Autowired ShiftRepository shifts;
    @Autowired UserRepository users;
    @Autowired ZoneRepository zones;
    @Autowired TariffService tariffService;
    @Autowired DeveloperActivityService activityService;
    @Autowired LicenseService licenseService;
    @Autowired LicenseInfoRepository licenseRepository;

    private final List<Runnable> cleanup = new ArrayList<>();

    @BeforeEach
    void init() {
        cleanup.clear();
    }

    @AfterEach
    void cleanUp() {
        for (int i = cleanup.size() - 1; i >= 0; i--) cleanup.get(i).run();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(body).get("token").asString();
    }

    private String dev() throws Exception { return login("gelistirici", "test-developer-pass1"); }
    private String admin() throws Exception { return login("admin", "test-admin-pass"); }
    private String officer() throws Exception { return login("tevfik", "test-officer-pass"); }

    private static final java.util.Random RND = new java.util.Random();

    /** Valid TR plate without 'I' (H2's LOWER() would turn it into a dotless i on a Turkish JVM). */
    private static String plate() {
        StringBuilder sb = new StringBuilder("34");
        for (int i = 0; i < 3; i++) sb.append("ABCDEFGHJKLMNOPRSTUVYZ".charAt(RND.nextInt(22)));
        for (int i = 0; i < 3; i++) sb.append(RND.nextInt(10));
        return sb.toString();
    }

    private void checkIn(String adm, String plate) throws Exception {
        mvc.perform(post("/api/parking/check-in").header("Authorization", adm)
                .param("plate", plate).param("street", "TEVFIK_BEY").param("zoneId", "1")).andExpect(status().isOk());
    }

    private long auditCount(AuditAction action, String actor) {
        return audits.findAll().stream()
                .filter(a -> a.getAction() == action && actor.equals(a.getActorUsername())).count();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> zone(Map<String, Object> activity, long zoneId) {
        return ((List<Map<String, Object>>) activity.get("zones")).stream()
                .filter(z -> ((Number) z.get("zoneId")).longValue() == zoneId).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> alerts(Map<String, Object> activity) {
        return (List<Map<String, Object>>) activity.get("alerts");
    }

    private static boolean hasAlert(Map<String, Object> activity, String code, String username) {
        return alerts(activity).stream().anyMatch(a -> code.equals(a.get("code"))
                && (username == null || username.equals(a.get("username"))));
    }

    private static Map<String, Object> alert(Map<String, Object> activity, String code, String username) {
        return alerts(activity).stream().filter(a -> code.equals(a.get("code"))
                && (username == null || username.equals(a.get("username")))).findFirst().orElseThrow();
    }

    /** A zone and an active officer nobody else uses, so alerts for them are deterministic. */
    private record Fixture(Zone zone, User officer) {}

    private Fixture fixture() {
        int number = 200 + RND.nextInt(700);
        Zone z = zones.save(new Zone(StreetLocation.CUMHURIYET, number, "Test Saha " + number, "l", "r"));
        User u = new User("izle" + UUID.randomUUID().toString().substring(0, 6), "x", Role.OFFICER, z);
        u = users.save(u);
        Zone zf = z;
        User uf = u;
        cleanup.add(() -> {
            shifts.deleteAll(shifts.findAll().stream().filter(s -> s.getUsername().equals(uf.getUsername())).toList());
            users.delete(users.findById(uf.getId()).orElseThrow());
            zones.delete(zf);
        });
        return new Fixture(z, u);
    }

    private void setSeen(User u, LocalDateTime at, String version) {
        jdbc.update("UPDATE users SET last_seen_at = ?, last_app_version = ?, last_device = ? WHERE id = ?",
                at, version, version == null ? null : "Test Cihaz / Android 13", u.getId());
    }

    private Shift openShift(Fixture f, LocalDateTime start) {
        return shifts.save(new Shift(f.officer(), f.zone(), "dev-" + f.officer().getUsername(), start));
    }

    /** A paid-hours morning the tariff understands: paid start of the current tariff on a fixed day. */
    private LocalDateTime paidStart() {
        Tariff t = tariffService.current();
        return LocalDate.of(2026, 11, 5).atTime(t.getPaidStart());
    }

    // ---- access ------------------------------------------------------------------------------------

    @Test
    void adminAndOfficerCannotUseDeveloperMonitoring() throws Exception {
        for (String token : List.of(admin(), officer())) {
            for (String path : List.of("/api/developer/audit", "/api/developer/audit/actions",
                    "/api/developer/activity")) {
                mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
            }
        }
        mvc.perform(get("/api/developer/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/developer/activity")).andExpect(status().isUnauthorized());
    }

    @Test
    void developerCannotUseOperationalOrExportEndpoints() throws Exception {
        String d = dev();
        mvc.perform(get("/api/admin/audit/export").header("Authorization", d)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit").header("Authorization", d)).andExpect(status().isForbidden());
    }

    // ---- developer audit -----------------------------------------------------------------------------

    @Test
    void auditIsMasked_plateSearchWorks_onlyPageZeroIsAudited() throws Exception {
        String adm = admin();
        String dv = dev();
        String p = plate();
        checkIn(adm, p);
        String masked = p.substring(0, 2) + "*".repeat(p.length() - 4) + p.substring(p.length() - 2);

        long before = auditCount(AuditAction.DEV_AUDIT_VIEWED, "gelistirici");
        String spaced = p.substring(0, 2) + " " + p.substring(2, 5).toLowerCase() + " " + p.substring(5);
        String body = mvc.perform(get("/api/developer/audit").header("Authorization", dv).param("q", spaced))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("VEHICLE_CHECK_IN"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(p), "full plate must not leave the server: " + body);
        assertTrue(body.contains(masked), "masked plate expected: " + body);
        assertEquals(before + 1, auditCount(AuditAction.DEV_AUDIT_VIEWED, "gelistirici"), "page 0 is audited once");

        // flipping pages does not create more rows
        mvc.perform(get("/api/developer/audit").header("Authorization", dv).param("page", "1").param("size", "5"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/developer/audit").header("Authorization", dv).param("page", "2").param("size", "5"))
                .andExpect(status().isOk());
        assertEquals(before + 1, auditCount(AuditAction.DEV_AUDIT_VIEWED, "gelistirici"));

        // the stored view record carries the filter but never the full plate
        AuditLog viewed = audits.findAll().stream()
                .filter(a -> a.getAction() == AuditAction.DEV_AUDIT_VIEWED).reduce((a, b) -> b).orElseThrow();
        assertTrue(viewed.getSummary().contains("q=" + masked), viewed.getSummary());
        assertFalse(viewed.getSummary().contains(p));

        // the admin sees the developer's look in his own audit screen
        mvc.perform(get("/api/admin/audit").header("Authorization", adm).param("action", "DEV_AUDIT_VIEWED").param("includeMaintenance", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actorUsername").value("gelistirici"));
    }

    @Test
    void auditMasksPlatesInSummaryBeforeAfterAndEntityId() throws Exception {
        String p = plate();
        AuditLog a = new AuditLog();
        a.setCreatedAt(LocalDateTime.now());
        a.setActorUsername("admin");
        a.setActorRole("ADMIN");
        a.setAction(AuditAction.SETTINGS_UPDATED);
        a.setEntityType("Test");
        a.setEntityId(p);
        a.setSummary("Düzeltme " + p + " için");
        a.setBeforeData("{\"plate\":\"" + p + "\",\"fullName\":\"Ali Veli\",\"nested\":[\"" + p + "\"]}");
        a.setAfterData("{\"note\":\"" + p.substring(0, 2) + " " + p.substring(2, 5) + " " + p.substring(5) + "\"}");
        a = audits.save(a);
        AuditLog saved = a;
        cleanup.add(() -> audits.delete(saved));

        String body = mvc.perform(get("/api/developer/audit").header("Authorization", dev())
                        .param("action", "SETTINGS_UPDATED").param("entityType", "Test").param("size", "200"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(p), body);
        assertFalse(body.contains("Ali Veli"), body);
        JsonNode row = null;
        for (JsonNode n : mapper.readTree(body).get("content")) {
            if (n.get("id").longValue() == saved.getId()) row = n;
        }
        assertNotNull(row);
        String m = p.substring(0, 2) + "*".repeat(4) + p.substring(6);
        assertEquals(m, row.get("entityId").asString());
        assertEquals("Düzeltme " + m + " için", row.get("summary").asString());
        assertEquals(m, row.get("before").get("plate").asString());
        assertEquals("***", row.get("before").get("fullName").asString());
        assertEquals(m, row.get("before").get("nested").get(0).asString());
    }

    @Test
    void auditSearchRefusesPartialPlateGuessing_andListsActions() throws Exception {
        String dv = dev();
        mvc.perform(get("/api/developer/audit").header("Authorization", dv).param("q", "34AB"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/developer/audit").header("Authorization", dv).param("q", "giriş"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/developer/audit/actions").header("Authorization", dv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='DEV_AUDIT_VIEWED')].label")
                        .value("Geliştirici: denetim kaydı görüntülendi"));
    }

    // ---- activity: counts ------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void activityCountsFollowTheRealFlow_andContainNoPlates() throws Exception {
        String adm = admin();
        String dv = dev();
        Map<String, Object> before = mapper.readValue(
                mvc.perform(get("/api/developer/activity").header("Authorization", dv)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), Map.class);
        Map<String, Object> z0 = zone(before, 1);

        String in1 = plate(), in2 = plate(), in3 = plate(), in4 = plate();
        for (String p : List.of(in1, in2, in3, in4)) checkIn(adm, p);
        mvc.perform(post("/api/parking/check-out").header("Authorization", adm).param("plate", in1))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/runaway").header("Authorization", adm).param("plate", in2))
                .andExpect(status().isOk());
        mvc.perform(post("/api/parking/spots/cancel").header("Authorization", adm).param("plate", in3)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"yanlış\"}"))
                .andExpect(status().isOk());

        String raw = mvc.perform(get("/api/developer/activity").header("Authorization", dv))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> after = mapper.readValue(raw, Map.class);
        Map<String, Object> z1 = zone(after, 1);

        assertEquals(4, delta(z1, z0, "checkIns"));
        assertEquals(1, delta(z1, z0, "checkOuts"));
        assertEquals(1, delta(z1, z0, "runaways"));
        assertEquals(1, delta(z1, z0, "cancellations"));
        assertEquals(1, delta(z1, z0, "activeVehicles")); // only in4 is still parked
        assertNotNull(z1.get("lastActivityAt"));
        assertEquals(24, ((Number) after.get("windowHours")).intValue());
        assertTrue(((Number) ((Map<String, Object>) after.get("sync")).get("liveConnections")).intValue() >= 0);

        for (String p : List.of(in1, in2, in3, in4)) assertFalse(raw.contains(p), "plate in activity: " + p);
        assertFalse(raw.contains("fullName"));
        assertFalse(raw.toLowerCase().contains("plate"));

        // the screen is polled every minute: it must not write audit rows
        long views = audits.findAll().stream().filter(a -> a.getAction().name().startsWith("DEV_")).count();
        mvc.perform(get("/api/developer/activity").header("Authorization", dv)).andExpect(status().isOk());
        assertEquals(views, audits.findAll().stream().filter(a -> a.getAction().name().startsWith("DEV_")).count());
    }

    private static int delta(Map<String, Object> after, Map<String, Object> before, String key) {
        return ((Number) after.get(key)).intValue() - ((Number) before.get(key)).intValue();
    }

    @Test
    void activityHoursIsValidated() throws Exception {
        String dv = dev();
        mvc.perform(get("/api/developer/activity").header("Authorization", dv).param("hours", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/developer/activity").header("Authorization", dv).param("hours", "169"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/developer/activity").header("Authorization", dv).param("hours", "168"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowHours").value(168))
                .andExpect(jsonPath("$.errors.byDay.length()").value(7))
                .andExpect(jsonPath("$.workingHours.start").exists());
    }

    // ---- activity: alerts with a pinned clock ----------------------------------------------------------

    @Test
    void shiftNotStarted_afterThirtyMinutesOfPaidHours() throws Exception {
        Fixture f = fixture();
        LocalDateTime start = paidStart();
        Map<String, Object> at29 = activityService.activity(24, start.plusMinutes(29));
        assertTrue(alerts(at29).stream().noneMatch(x -> "SHIFT_NOT_STARTED".equals(x.get("code"))
                && f.zone().getId().equals(x.get("zoneId"))), "29 minutes: not yet");
        Map<String, Object> at31 = activityService.activity(24, start.plusMinutes(31));
        Map<String, Object> a = alerts(at31).stream().filter(x -> "SHIFT_NOT_STARTED".equals(x.get("code"))
                && f.zone().getId().equals(x.get("zoneId"))).findFirst().orElseThrow();
        assertEquals("WARN", a.get("level"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) at31.get("workingHours")).get("now"));

        // once a shift is open the alert goes away
        openShift(f, start.plusMinutes(5));
        setSeen(f.officer(), start.plusMinutes(30), "1.0.0+1");
        Map<String, Object> opened = activityService.activity(24, start.plusMinutes(31));
        assertTrue(alerts(opened).stream().noneMatch(x -> "SHIFT_NOT_STARTED".equals(x.get("code"))
                && f.zone().getId().equals(x.get("zoneId"))));
        assertNotNull(zone(opened, f.zone().getId()).get("openShift"));
    }

    @Test
    void outsidePaidHoursThereAreNoShiftOrIdleAlerts() {
        Fixture f = fixture();
        LocalDateTime start = paidStart();
        openShift(f, start.minusHours(3));
        setSeen(f.officer(), start.minusHours(3), "1.0.0+1");
        LocalDateTime night = start.minusHours(1); // before the paid window opens
        Map<String, Object> act = activityService.activity(24, night);
        assertEquals(Boolean.FALSE, ((Map<?, ?>) act.get("workingHours")).get("now"));
        assertTrue(alerts(act).stream().noneMatch(x -> List.of("SHIFT_NOT_STARTED", "ZONE_IDLE")
                .contains(x.get("code")) && f.zone().getId().equals(x.get("zoneId"))));
    }

    @Test
    void deviceOffline_whenOpenShiftOfficerNotSeenForFifteenMinutes() {
        Fixture f = fixture();
        LocalDateTime start = paidStart();
        openShift(f, start);
        setSeen(f.officer(), start, "1.0.0+1");

        assertFalse(hasAlert(activityService.activity(24, start.plusMinutes(14)), "DEVICE_OFFLINE",
                f.officer().getUsername()));
        Map<String, Object> a = alert(activityService.activity(24, start.plusMinutes(22)), "DEVICE_OFFLINE",
                f.officer().getUsername());
        assertEquals("WARN", a.get("level"));
        assertEquals(f.officer().getUsername() + " (" + f.zone().getZoneName() + ") 22 dakikadır görülmedi, mesai açık.",
                a.get("message"));
        assertEquals(f.zone().getId(), ((Number) a.get("zoneId")).longValue());

        // the officer's phone checks in again: alert gone, and the zone card shows the trace
        setSeen(f.officer(), start.plusMinutes(21), "1.0.0+1");
        Map<String, Object> back = activityService.activity(24, start.plusMinutes(22));
        assertFalse(hasAlert(back, "DEVICE_OFFLINE", f.officer().getUsername()));
        @SuppressWarnings("unchecked")
        Map<String, Object> o = ((List<Map<String, Object>>) zone(back, f.zone().getId()).get("officers")).get(0);
        assertEquals("Test Cihaz / Android 13", o.get("device"));
        assertEquals("1.0.0+1", o.get("appVersion"));
        assertEquals(Boolean.FALSE, o.get("liveConnected"));
    }

    @Test
    void zoneIdle_afterNinetyMinutesWithoutMovement_infoLevel() {
        Fixture f = fixture();
        LocalDateTime start = paidStart();
        openShift(f, start);
        LocalDateTime now = start.plusMinutes(95);
        setSeen(f.officer(), now.minusMinutes(1), "1.0.0+1"); // phone alive, just no cars

        Map<String, Object> act = activityService.activity(24, now);
        Map<String, Object> a = alert(act, "ZONE_IDLE", f.officer().getUsername());
        assertEquals("INFO", a.get("level"));
        assertFalse(hasAlert(act, "DEVICE_OFFLINE", f.officer().getUsername()));
        assertFalse(hasAlert(activityService.activity(24, start.plusMinutes(80)), "ZONE_IDLE", f.officer().getUsername()));
    }

    @Test
    void outdatedApp_latestAndOutdatedUsers() {
        Fixture f = fixture();
        User admin = users.findByUsername("admin").orElseThrow();
        setSeen(f.officer(), paidStart(), "1.0.0+1");
        setSeen(admin, paidStart(), "9.9.9+9");
        cleanup.add(() -> jdbc.update("UPDATE users SET last_seen_at = NULL, last_app_version = NULL, "
                + "last_device = NULL WHERE username = 'admin'"));

        Map<String, Object> act = activityService.activity(24, paidStart());
        @SuppressWarnings("unchecked")
        Map<String, Object> versions = (Map<String, Object>) act.get("versions");
        assertEquals("9.9.9+9", versions.get("latest"));
        assertTrue(((List<?>) versions.get("outdatedUsers")).contains(f.officer().getUsername()));
        assertEquals("INFO", alert(act, "OUTDATED_APP", null).get("level"));
    }

    @Test
    void failedLoginsAndErrorSpike_andSortingCritFirst() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 12, 0);
        List<AuditLog> failed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AuditLog a = new AuditLog();
            a.setCreatedAt(now.minusMinutes(10 + i));
            a.setActorUsername("hedefkisi");
            a.setAction(AuditAction.LOGIN_FAILED);
            failed.add(audits.save(a));
        }
        List<ErrorLog> errs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ErrorLog e = new ErrorLog();
            e.setCreatedAt(now.minusMinutes(5 + i));
            e.setErrorCode("INTERNAL_ERROR");
            e.setPath("/api/test-hata");
            errs.add(errorLogs.save(e));
        }
        cleanup.add(() -> audits.deleteAll(failed));
        cleanup.add(() -> errorLogs.deleteAll(errs));

        Map<String, Object> act = activityService.activity(24, now);
        assertEquals("WARN", alert(act, "FAILED_LOGINS", "hedefkisi").get("level"));
        assertEquals("CRIT", alert(act, "ERROR_SPIKE", null).get("level"));
        assertFalse(hasAlert(act, "ERRORS_PRESENT", null), "spike replaces the plain errors warning");

        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) act.get("errors");
        assertTrue(((Number) errors.get("last24h")).intValue() >= 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) errors.get("topCodes");
        assertEquals("INTERNAL_ERROR", top.get(0).get("code"));
        assertEquals("/api/test-hata", top.get(0).get("path"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byDay = (List<Map<String, Object>>) errors.get("byDay");
        assertEquals(7, byDay.size());
        assertEquals("2026-11-05", byDay.get(6).get("date"));
        assertTrue(((Number) byDay.get(6).get("count")).intValue() >= 10);

        // CRIT before WARN before INFO
        List<String> order = List.of("CRIT", "WARN", "INFO");
        int last = -1;
        for (Map<String, Object> a : alerts(act)) {
            int idx = order.indexOf((String) a.get("level"));
            assertTrue(idx >= last, "alerts are sorted by level: " + alerts(act));
            last = idx;
        }

        // a quiet hour later the spike is over: only the 24 h warning stays
        Map<String, Object> later = activityService.activity(24, now.plusHours(2));
        assertFalse(hasAlert(later, "ERROR_SPIKE", null));
        assertTrue(hasAlert(later, "ERRORS_PRESENT", null));
        assertFalse(hasAlert(later, "FAILED_LOGINS", "hedefkisi"));
    }

    @Test
    void offlineCheckInDelayIsMeasuredFromTheAuditRow() {
        Fixture f = fixture();
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 12, 0);
        AuditLog a = new AuditLog();
        a.setCreatedAt(now.minusMinutes(1)); // the request reached the server
        a.setActorUsername(f.officer().getUsername());
        a.setAction(AuditAction.VEHICLE_CHECK_IN);
        a.setEntityType("ParkSpot");
        a.setEntityId("0");
        a.setAfterData("{\"zoneId\":" + f.zone().getId() + ",\"entryTime\":\"" + now.minusMinutes(1).minusSeconds(700)
                + "\",\"entryTimeSource\":\"CLIENT\"}");
        AuditLog saved = audits.save(a);
        cleanup.add(() -> audits.delete(saved));

        Map<String, Object> act = activityService.activity(24, now);
        @SuppressWarnings("unchecked")
        Map<String, Object> sync = (Map<String, Object>) act.get("sync");
        assertTrue(((Number) sync.get("offlineCheckIns")).intValue() >= 1);
        assertTrue(((Number) sync.get("maxOfflineDelaySeconds")).longValue() >= 700);
        assertEquals("WARN", alert(act, "OFFLINE_DELAY", null).get("level"));
        assertEquals(1, ((Number) zone(act, f.zone().getId()).get("checkIns")).intValue());
    }

    @Test
    void licenseSoon_warnsAtThirtyDays() {
        LicenseInfo l = licenseService.current();
        LocalDate savedExpiry = l.getExpiresOn();
        cleanup.add(() -> {
            LicenseInfo cur = licenseService.current();
            cur.setExpiresOn(savedExpiry);
            licenseRepository.save(cur);
            licenseService.evictCache();
        });
        l.setExpiresOn(LocalDate.now().plusDays(10));
        licenseRepository.save(l);
        licenseService.evictCache();

        Map<String, Object> act = activityService.activity(24, LocalDateTime.now());
        assertEquals("WARN", alert(act, "LICENSE_SOON", null).get("level"));

        l = licenseService.current();
        l.setExpiresOn(LocalDate.now().plusDays(200));
        licenseRepository.save(l);
        licenseService.evictCache();
        assertFalse(hasAlert(activityService.activity(24, LocalDateTime.now()), "LICENSE_SOON", null));
    }

    // ---- health extras and admin user list -----------------------------------------------------------------

    @Test
    void healthHasAppVersionAndStartedAt() throws Exception {
        mvc.perform(get("/api/developer/health").header("Authorization", dev()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.appVersion").hasJsonPath());
    }

    @Test
    void adminUserListShowsDeviceTrace() throws Exception {
        Fixture f = fixture();
        LocalDateTime seen = LocalDateTime.of(2026, 11, 5, 9, 30, 0);
        setSeen(f.officer(), seen, "1.2.3+4");
        mvc.perform(get("/api/admin/users").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='" + f.officer().getUsername() + "')].appVersion").value("1.2.3+4"))
                .andExpect(jsonPath("$[?(@.username=='" + f.officer().getUsername() + "')].device")
                        .value("Test Cihaz / Android 13"))
                .andExpect(jsonPath("$[?(@.username=='" + f.officer().getUsername() + "')].lastSeenAt").exists());
    }
}
