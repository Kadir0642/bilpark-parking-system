package com.bilpark.backend.developer;

import com.bilpark.backend.license.LicenseService;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.ShiftRepository;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.repository.ZoneRepository;
import com.bilpark.backend.service.TariffService;
import com.bilpark.backend.settings.SettingsService;
import com.bilpark.backend.sync.SyncService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * GET /api/developer/activity (docs/api-gelistirici-izleme.md section 2). Read-only, plate-free, not audited.
 * All numbers come from existing tables; nothing is written.
 */
@Service
public class DeveloperActivityService {

    // ---- alert thresholds: the only place ----------------------------------------------------------
    static final long DB_SLOW_MS = 500;
    static final int ERROR_SPIKE_PER_HOUR = 10;
    static final int SHIFT_NOT_STARTED_AFTER_MIN = 30;
    static final int DEVICE_OFFLINE_AFTER_MIN = 15;
    static final int ZONE_IDLE_AFTER_MIN = 90;
    static final int OFFLINE_ENTRY_MIN_SECONDS = 60;
    static final int OFFLINE_DELAY_ALERT_SECONDS = 600;
    static final int FAILED_LOGINS_PER_HOUR = 5;
    static final int LICENSE_SOON_DAYS = 30;
    static final int RETENTION_STALE_HOURS = 48;
    static final int LAST_ACTIVITY_LOOKBACK_DAYS = 30;
    public static final int MAX_HOURS = 168;

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final ZoneRepository zoneRepository;
    private final UserRepository userRepository;
    private final ShiftRepository shiftRepository;
    private final SyncService syncService;
    private final TariffService tariffService;
    private final LicenseService licenseService;
    private final SettingsService settingsService;
    private final JsonMapper mapper;
    private final Clock clock;

    public DeveloperActivityService(JdbcTemplate jdbc, ZoneRepository zoneRepository, UserRepository userRepository,
                                    ShiftRepository shiftRepository, SyncService syncService,
                                    TariffService tariffService, LicenseService licenseService,
                                    SettingsService settingsService, JsonMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.zoneRepository = zoneRepository;
        this.userRepository = userRepository;
        this.shiftRepository = shiftRepository;
        this.syncService = syncService;
        this.tariffService = tariffService;
        this.licenseService = licenseService;
        this.settingsService = settingsService;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Map<String, Object> activity(int hours) {
        return activity(hours, LocalDateTime.now(clock));
    }

    /** {@code now} is a parameter so tests can pin the time. */
    public Map<String, Object> activity(int hours, LocalDateTime now) {
        LocalDateTime windowStart = now.minusHours(hours);
        List<Map<String, Object>> alerts = new ArrayList<>();

        long dbMs = -1;
        long t0 = System.nanoTime();
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            if (one != null && one == 1) dbMs = (System.nanoTime() - t0) / 1_000_000;
        } catch (RuntimeException ignored) {
            // reported as DB_DOWN
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now);
        out.put("windowHours", hours);
        if (dbMs < 0) {
            alert(alerts, "CRIT", "DB_DOWN", "Veritabanına ulaşılamıyor.", null, null);
            out.put("workingHours", null);
            out.put("zones", List.of());
            out.put("sync", Map.of("liveConnections", syncService.connectedCount(), "offlineCheckIns", 0,
                    "maxOfflineDelaySeconds", 0));
            out.put("logins", Map.of("success", 0, "failed", 0));
            out.put("errors", null);
            out.put("versions", null);
            out.put("alerts", sorted(alerts));
            return out;
        }
        if (dbMs > DB_SLOW_MS) {
            alert(alerts, "WARN", "DB_SLOW", "Veritabanı yavaş yanıt veriyor (" + dbMs + " ms).", null, null);
        }

        // ---- paid hours ----
        Tariff tariff = tariffService.current();
        PaidWindow paid = PaidWindow.of(tariff.getPaidStart(), tariff.getPaidEnd(), now);
        Map<String, Object> working = new LinkedHashMap<>();
        working.put("start", HHMM.format(paid.start));
        working.put("end", HHMM.format(paid.end));
        working.put("now", paid.inWindow);
        out.put("workingHours", working);

        // ---- people and shifts ----
        List<Zone> zones = new ArrayList<>(zoneRepository.findAll());
        zones.sort(Comparator.comparing(Zone::getId));
        List<User> allUsers = userRepository.findAll();
        Set<Long> live = syncService.connectedUserIds();
        Map<Long, List<User>> officersByZone = new HashMap<>();
        Map<String, User> byUsername = new HashMap<>();
        for (User u : allUsers) {
            byUsername.put(u.getUsername(), u);
            if (u.getRole() == Role.OFFICER && u.isActive() && u.getAssignedZone() != null) {
                officersByZone.computeIfAbsent(u.getAssignedZone().getId(), k -> new ArrayList<>()).add(u);
            }
        }
        Map<Long, Shift> openShiftByZone = new HashMap<>();
        for (Shift s : shiftRepository.findByEndTimeIsNullOrderByStartTimeAsc()) {
            if (s.getZone() != null) openShiftByZone.put(s.getZone().getId(), s); // ascending: the latest wins
        }

        // ---- counts ----
        Map<Long, Map<String, Integer>> counts = zoneCounts(windowStart);
        Map<Long, Integer> activeVehicles = new HashMap<>();
        jdbc.query("SELECT zone_id, COUNT(*) FROM active_park_spots WHERE zone_id IS NOT NULL GROUP BY zone_id",
                rs -> {
                    activeVehicles.put(rs.getLong(1), rs.getInt(2));
                });
        Map<Long, LocalDateTime> lastActivity = lastActivity(now);
        OfflineStats offline = offlineStats(windowStart, counts);

        // ---- zones ----
        List<Map<String, Object>> zoneList = new ArrayList<>();
        for (Zone z : zones) {
            Map<String, Integer> c = counts.getOrDefault(z.getId(), Map.of());
            Shift shift = openShiftByZone.get(z.getId());
            List<User> officers = officersByZone.getOrDefault(z.getId(), List.of());
            Map<String, Object> zm = new LinkedHashMap<>();
            zm.put("zoneId", z.getId());
            zm.put("zoneName", z.getZoneName());
            zm.put("street", z.getStreet() != null ? z.getStreet().getDisplayName() : null);
            zm.put("activeVehicles", activeVehicles.getOrDefault(z.getId(), 0));
            zm.put("checkIns", c.getOrDefault("in", 0));
            zm.put("checkOuts", c.getOrDefault("out", 0));
            zm.put("runaways", c.getOrDefault("runaway", 0));
            zm.put("cancellations", c.getOrDefault("cancel", 0));
            LocalDateTime last = lastActivity.get(z.getId());
            zm.put("lastActivityAt", last);
            if (shift != null) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("username", shift.getUsername());
                sm.put("startedAt", shift.getStartTime());
                sm.put("deviceId", shift.getDeviceId());
                zm.put("openShift", sm);
            } else {
                zm.put("openShift", null);
            }
            List<Map<String, Object>> officerList = new ArrayList<>();
            for (User u : officers) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("username", u.getUsername());
                om.put("lastSeenAt", u.getLastSeenAt());
                om.put("appVersion", u.getLastAppVersion());
                om.put("device", u.getLastDevice());
                om.put("liveConnected", live.contains(u.getId()));
                officerList.add(om);
            }
            officerList.sort(Comparator.comparing(m -> (String) m.get("username")));
            zm.put("officers", officerList);
            zoneList.add(zm);

            // zone alerts
            if (paid.inWindow && shift == null && !officers.isEmpty()
                    && Duration.between(paid.startMoment, now).toMinutes() >= SHIFT_NOT_STARTED_AFTER_MIN) {
                alert(alerts, "WARN", "SHIFT_NOT_STARTED", z.getZoneName() + ": ücretli saat başladı, mesai açılmadı.",
                        z.getId(), null);
            }
            if (shift != null) {
                User who = byUsername.get(shift.getUsername());
                LocalDateTime seen = who != null && who.getLastSeenAt() != null ? who.getLastSeenAt() : shift.getStartTime();
                long offMin = Duration.between(seen, now).toMinutes();
                if (offMin >= DEVICE_OFFLINE_AFTER_MIN) {
                    alert(alerts, "WARN", "DEVICE_OFFLINE", shift.getUsername() + " (" + z.getZoneName() + ") " + offMin
                            + " dakikadır görülmedi, mesai açık.", z.getId(), shift.getUsername());
                }
                if (paid.inWindow) {
                    LocalDateTime ref = last != null && last.isAfter(shift.getStartTime()) ? last : shift.getStartTime();
                    long idle = Duration.between(ref, now).toMinutes();
                    if (idle >= ZONE_IDLE_AFTER_MIN) {
                        alert(alerts, "INFO", "ZONE_IDLE", z.getZoneName() + ": mesai açık, " + idle
                                + " dakikadır hareket yok.", z.getId(), shift.getUsername());
                    }
                }
            }
        }
        out.put("zones", zoneList);

        // ---- sync ----
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("liveConnections", syncService.connectedCount());
        sync.put("offlineCheckIns", offline.count);
        sync.put("maxOfflineDelaySeconds", offline.maxDelaySeconds);
        out.put("sync", sync);
        if (offline.maxDelaySeconds > OFFLINE_DELAY_ALERT_SECONDS) {
            alert(alerts, "WARN", "OFFLINE_DELAY", "Çevrimdışı giriş " + (offline.maxDelaySeconds / 60)
                    + " dakika gecikmeyle sunucuya ulaştı.", null, null);
        }

        // ---- logins ----
        Map<String, Integer> loginCounts = new HashMap<>();
        jdbc.query("SELECT action, COUNT(*) FROM audit_logs WHERE action IN ('LOGIN', 'LOGIN_FAILED') "
                + "AND created_at >= ? AND created_at <= ? GROUP BY action", rs -> {
            loginCounts.put(rs.getString(1), rs.getInt(2));
        }, windowStart, now);
        Map<String, Object> logins = new LinkedHashMap<>();
        logins.put("success", loginCounts.getOrDefault("LOGIN", 0));
        logins.put("failed", loginCounts.getOrDefault("LOGIN_FAILED", 0));
        out.put("logins", logins);
        jdbc.query("SELECT actor_username, COUNT(*) FROM audit_logs WHERE action = 'LOGIN_FAILED' "
                        + "AND created_at >= ? AND created_at <= ? GROUP BY actor_username HAVING COUNT(*) >= ?",
                rs -> {
                    alert(alerts, "WARN", "FAILED_LOGINS", rs.getInt(2) + " başarısız giriş denemesi: " + rs.getString(1)
                            + " (son 1 saat).", null, rs.getString(1));
                }, now.minusHours(1), now, FAILED_LOGINS_PER_HOUR);

        // ---- errors ----
        out.put("errors", errors(now, alerts));

        // ---- versions ----
        Optional<AppVersion> latest = allUsers.stream().filter(User::isActive)
                .map(u -> AppVersion.parse(u.getLastAppVersion())).flatMap(Optional::stream)
                .max(Comparator.naturalOrder());
        List<String> outdated = new ArrayList<>();
        String latestText = null;
        if (latest.isPresent()) {
            latestText = allUsers.stream().filter(User::isActive)
                    .filter(u -> AppVersion.parse(u.getLastAppVersion()).filter(latest.get()::equals).isPresent())
                    .map(User::getLastAppVersion).findFirst().orElse(null);
            for (User u : allUsers) {
                if (u.getRole() != Role.OFFICER || !u.isActive()) continue;
                Optional<AppVersion> v = AppVersion.parse(u.getLastAppVersion());
                if (v.isPresent() && v.get().compareTo(latest.get()) < 0) outdated.add(u.getUsername());
            }
            outdated.sort(Comparator.naturalOrder());
        }
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("latest", latestText);
        versions.put("outdatedUsers", outdated);
        out.put("versions", versions);
        if (!outdated.isEmpty()) {
            alert(alerts, "INFO", "OUTDATED_APP", "Eski uygulama sürümü kullanan memur: " + String.join(", ", outdated)
                    + " (güncel: " + latestText + ").", null, null);
        }

        // ---- license, retention ----
        try {
            LicenseService.LicenseStatus lic = licenseService.status();
            if (lic.readOnly()) {
                alert(alerts, "CRIT", "LICENSE_SOON", "Lisans bitti, sistem okunur modda.", null, null);
            } else if (lic.daysLeft() <= LICENSE_SOON_DAYS) {
                alert(alerts, "WARN", "LICENSE_SOON", lic.daysLeft() >= 0
                        ? "Lisansın bitmesine " + lic.daysLeft() + " gün kaldı."
                        : "Lisans süresi doldu, ek süredesiniz (" + lic.graceDaysLeft() + " gün kaldı).", null, null);
            }
        } catch (RuntimeException ignored) {
            // license row unreadable: the DB checks above already report trouble
        }
        retentionAlert(now, alerts);

        out.put("alerts", sorted(alerts));
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** zone id to {in,out,runaway,cancel} within the window, from the audit log. */
    private Map<Long, Map<String, Integer>> zoneCounts(LocalDateTime windowStart) {
        Map<Long, Map<String, Integer>> counts = new HashMap<>();
        jdbc.query("SELECT r.zone_id, a.action, COUNT(*) FROM audit_logs a JOIN parking_records r "
                + "ON CAST(r.id AS VARCHAR(100)) = a.entity_id WHERE a.entity_type = 'ParkingRecord' "
                + "AND a.action IN ('VEHICLE_CHECK_OUT', 'VEHICLE_RUNAWAY', 'SPOT_CANCELLED', 'RECORD_CANCELLED') "
                + "AND a.created_at >= ? AND r.zone_id IS NOT NULL GROUP BY r.zone_id, a.action", rs -> {
            long zone = rs.getLong(1);
            String key = switch (rs.getString(2)) {
                case "VEHICLE_CHECK_OUT" -> "out";
                case "VEHICLE_RUNAWAY" -> "runaway";
                default -> "cancel"; // SPOT_CANCELLED, RECORD_CANCELLED
            };
            counts.computeIfAbsent(zone, k -> new HashMap<>()).merge(key, rs.getInt(3), Integer::sum);
        }, windowStart);
        return counts;
    }

    private record OfflineStats(int count, long maxDelaySeconds) {}

    /**
     * Check-ins (audit rows carry zoneId and the resolved entry time): adds the per-zone "in" count and finds
     * the offline ones, i.e. entry time taken from the officer's device (source CLIENT) more than 60 s before
     * the audit row was written (= when the request reached the server).
     */
    private OfflineStats offlineStats(LocalDateTime windowStart, Map<Long, Map<String, Integer>> counts) {
        int[] offline = {0};
        long[] max = {0};
        jdbc.query("SELECT created_at, after_data FROM audit_logs WHERE action = 'VEHICLE_CHECK_IN' "
                + "AND created_at >= ?", rs -> {
            LocalDateTime arrived = rs.getTimestamp(1).toLocalDateTime();
            String json = rs.getString(2);
            if (json == null) return;
            try {
                JsonNode n = mapper.readTree(json);
                JsonNode zone = n.path("zoneId");
                if (zone.isNumber()) {
                    counts.computeIfAbsent(zone.longValue(), k -> new HashMap<>()).merge("in", 1, Integer::sum);
                }
                if ("CLIENT".equals(n.path("entryTimeSource").asString(""))) {
                    String entry = n.path("entryTime").asString("");
                    if (!entry.isEmpty()) {
                        long delay = Duration.between(LocalDateTime.parse(entry), arrived).getSeconds();
                        if (delay > OFFLINE_ENTRY_MIN_SECONDS) {
                            offline[0]++;
                            max[0] = Math.max(max[0], delay);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // an unreadable audit payload only loses its numbers
            }
        }, windowStart);
        return new OfflineStats(offline[0], max[0]);
    }

    /** Latest entry / exit / cancellation per zone, looking back 30 days (can be older than the window). */
    private Map<Long, LocalDateTime> lastActivity(LocalDateTime now) {
        Timestamp since = Timestamp.valueOf(now.minusDays(LAST_ACTIVITY_LOOKBACK_DAYS));
        Map<Long, LocalDateTime> map = new HashMap<>();
        jdbc.query("SELECT zone_id, MAX(t) FROM ("
                + "SELECT zone_id, entry_time AS t FROM parking_records WHERE entry_time >= ? "
                + "UNION ALL SELECT zone_id, exit_time FROM parking_records WHERE exit_time >= ? "
                + "UNION ALL SELECT zone_id, cancelled_at FROM parking_records WHERE cancelled_at >= ? "
                + "UNION ALL SELECT zone_id, entry_time FROM active_park_spots WHERE entry_time >= ?"
                + ") x WHERE zone_id IS NOT NULL GROUP BY zone_id", rs -> {
            Timestamp ts = rs.getTimestamp(2);
            if (ts != null) map.put(rs.getLong(1), ts.toLocalDateTime());
        }, since, since, since, since);
        return map;
    }

    private Map<String, Object> errors(LocalDateTime now, List<Map<String, Object>> alerts) {
        long last24 = count("SELECT COUNT(*) FROM error_logs WHERE created_at >= ? AND created_at <= ?",
                now.minusHours(24), now);
        long lastHour = count("SELECT COUNT(*) FROM error_logs WHERE created_at >= ? AND created_at <= ?",
                now.minusHours(1), now);

        LocalDate today = now.toLocalDate();
        TreeMap<LocalDate, Integer> days = new TreeMap<>();
        for (int i = 6; i >= 0; i--) days.put(today.minusDays(i), 0);
        jdbc.query("SELECT CAST(created_at AS DATE), COUNT(*) FROM error_logs WHERE created_at >= ? AND created_at <= ? "
                + "GROUP BY CAST(created_at AS DATE)", rs -> {
            LocalDate d = rs.getDate(1).toLocalDate();
            if (days.containsKey(d)) days.put(d, rs.getInt(2));
        }, today.minusDays(6).atStartOfDay(), now);
        List<Map<String, Object>> byDay = new ArrayList<>();
        days.forEach((d, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("count", n);
            byDay.add(m);
        });

        List<Map<String, Object>> top = new ArrayList<>();
        jdbc.query("SELECT error_code, path, COUNT(*) AS c FROM error_logs WHERE created_at >= ? AND created_at <= ? "
                + "GROUP BY error_code, path ORDER BY c DESC, error_code, path LIMIT 5", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString(1));
            m.put("path", rs.getString(2));
            m.put("count", rs.getInt(3));
            top.add(m);
        }, now.minusHours(24), now);

        if (lastHour >= ERROR_SPIKE_PER_HOUR) {
            alert(alerts, "CRIT", "ERROR_SPIKE", "Son 1 saatte " + lastHour + " hata oluştu.", null, null);
        } else if (last24 >= 1) {
            alert(alerts, "WARN", "ERRORS_PRESENT", "Son 24 saatte " + last24 + " hata kaydedildi.", null, null);
        }
        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("last24h", last24);
        errors.put("byDay", byDay);
        errors.put("topCodes", top);
        return errors;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private void retentionAlert(LocalDateTime now, List<Map<String, Object>> alerts) {
        try {
            Optional<String> raw = settingsService.get(SettingsService.KEY_RETENTION_LAST_RUN);
            LocalDateTime ranAt = null;
            if (raw.isPresent()) {
                String s = mapper.readTree(raw.get()).path("ranAt").asString("");
                if (!s.isEmpty()) ranAt = LocalDateTime.parse(s);
            }
            if (ranAt == null) {
                // never ran: only suspicious once the app has been up longer than the limit
                long upHours = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()).toHours();
                if (upHours >= RETENTION_STALE_HOURS) {
                    alert(alerts, "WARN", "RETENTION_STALE", "Saklama süresi işi hiç çalışmadı.", null, null);
                }
            } else if (Duration.between(ranAt, now).toHours() >= RETENTION_STALE_HOURS) {
                alert(alerts, "WARN", "RETENTION_STALE", "Saklama süresi işi " + Duration.between(ranAt, now).toHours()
                        + " saattir çalışmadı.", null, null);
            }
        } catch (RuntimeException ignored) {
            // unreadable setting: nothing to say
        }
    }

    private static void alert(List<Map<String, Object>> alerts, String level, String code, String message,
                              Long zoneId, String username) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("level", level);
        a.put("code", code);
        a.put("message", message);
        a.put("zoneId", zoneId);
        a.put("username", username);
        alerts.add(a);
    }

    private static List<Map<String, Object>> sorted(List<Map<String, Object>> alerts) {
        List<String> order = List.of("CRIT", "WARN", "INFO");
        List<Map<String, Object>> copy = new ArrayList<>(alerts);
        copy.sort(Comparator.comparingInt(a -> order.indexOf((String) a.get("level")))); // stable
        return copy;
    }

    /** Today's (or, past midnight of an overnight window, yesterday's) paid window and whether now is inside. */
    static final class PaidWindow {
        final LocalTime start;
        final LocalTime end;
        final LocalDateTime startMoment;
        final boolean inWindow;

        private PaidWindow(LocalTime start, LocalTime end, LocalDateTime startMoment, boolean inWindow) {
            this.start = start;
            this.end = end;
            this.startMoment = startMoment;
            this.inWindow = inWindow;
        }

        static PaidWindow of(LocalTime start, LocalTime end, LocalDateTime now) {
            LocalTime s = start != null ? start : LocalTime.of(8, 0);
            LocalTime e = end != null ? end : LocalTime.MIDNIGHT;
            LocalDateTime moment = now.toLocalDate().atTime(s);
            if (moment.isAfter(now)) moment = moment.minusDays(1);
            long lengthMin = (Duration.between(s, e).toMinutes() + 1440) % 1440;
            if (lengthMin == 0) lengthMin = 1440; // 00:00-00:00 = all day
            boolean in = now.isBefore(moment.plusMinutes(lengthMin));
            return new PaidWindow(s, e, moment, in);
        }
    }
}
