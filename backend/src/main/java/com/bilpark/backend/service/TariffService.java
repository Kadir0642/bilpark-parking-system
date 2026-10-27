package com.bilpark.backend.service;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.dto.TariffRequest;
import com.bilpark.backend.dto.VehiclePriceRequest;
import com.bilpark.backend.fee.DaySegment;
import com.bilpark.backend.fee.FeeBreakdown;
import com.bilpark.backend.fee.FeeEngine;
import com.bilpark.backend.fee.TariffRules;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.TariffStatus;
import com.bilpark.backend.model.TariffVehiclePrice;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.model.VehicleTypes;
import com.bilpark.backend.repository.TariffRepository;
import com.bilpark.backend.sync.ChangePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * Tariff versions: the ACTIVE and the SCHEDULED one are cached in memory, loaded once at startup
 * (no periodic DB polling, so a serverless DB can sleep). A scheduled tariff is activated by a
 * one-shot timer at its effectiveFrom, and lazily by current() if the timer was missed.
 */
@Service
public class TariffService {

    private static final Logger log = LoggerFactory.getLogger(TariffService.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final TariffRepository tariffRepository;
    private final ChangePublisher changePublisher;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;
    private final TaskScheduler taskScheduler;
    private final TransactionTemplate tx;
    private final AuditService auditService;
    private final VehicleTypeService vehicleTypeService;

    private volatile Tariff active;
    private volatile Tariff scheduled;
    private ScheduledFuture<?> timer;

    public TariffService(TariffRepository tariffRepository, ChangePublisher changePublisher, Clock clock,
                         PasswordEncoder passwordEncoder, TaskScheduler taskScheduler,
                         PlatformTransactionManager txManager, AuditService auditService,
                         VehicleTypeService vehicleTypeService) {
        this.auditService = auditService;
        this.vehicleTypeService = vehicleTypeService;
        this.tariffRepository = tariffRepository;
        this.changePublisher = changePublisher;
        this.clock = clock;
        this.passwordEncoder = passwordEncoder;
        this.taskScheduler = taskScheduler;
        this.tx = new TransactionTemplate(txManager);
    }

    // --- startup ---

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        // Vehicle types first: the default tariff seed below needs the SMALL/LARGE rows to exist (FKs)
        vehicleTypeService.ensureSeeded();
        tx.executeWithoutResult(s -> {
            // H2 tests run without Flyway: seed the default tariff if the table is empty
            if (tariffRepository.count() == 0) {
                tariffRepository.save(defaultTariff());
            }
            refreshCache();
            if (active != null) {
                vehicleTypeService.syncActiveFrom(active);
            }
        });
        Tariff s = scheduled;
        if (s != null) {
            if (isDue(s)) {
                activateDue();
            } else {
                scheduleTimer(s);
            }
        }
    }

    // --- reading ---

    /** The tariff that applies right now (activates a due scheduled tariff first). */
    public Tariff current() {
        Tariff s = scheduled;
        if (s != null && isDue(s)) {
            activateDue();
        }
        Tariff a = active;
        if (a == null) {
            log.warn("No ACTIVE tariff found; using the built-in default.");
            return defaultTariff();
        }
        return a;
    }

    /** The SCHEDULED tariff, or null. */
    public Tariff upcoming() {
        return scheduled;
    }

    /** Public view: current tariff plus "upcoming" (scheduled tariff or null). Also the TARIFF_UPDATED payload. */
    public Map<String, Object> currentWithUpcoming() {
        Map<String, Object> m = toPublicMap(current());
        Tariff s = scheduled;
        m.put("upcoming", s != null ? toPublicMap(s) : null);
        return m;
    }

    public Map<String, Object> toPublicMap(Tariff t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("status", t.getStatus() != null ? t.getStatus().name() : null);
        m.put("effectiveFrom", t.getEffectiveFrom() != null ? ISO.format(t.getEffectiveFrom()) : null);
        TariffVehiclePrice small = t.priceOf(VehicleTypes.SMALL);
        TariffVehiclePrice large = t.priceOf(VehicleTypes.LARGE);
        // legacy fields: null when the type has no row in this version
        m.put("small", small != null ? priceMap(small.getFirstHour(), small.getExtraHour()) : null);
        m.put("large", large != null ? priceMap(large.getFirstHour(), large.getExtraHour()) : null);
        m.put("freeMinutes", t.getFreeMinutes());
        m.put("paidStart", t.getPaidStart().format(HHMM));
        m.put("paidEnd", t.getPaidEnd().format(HHMM));
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("small", small != null ? small.getSubscriptionMonthly() : null);
        sub.put("large", large != null ? large.getSubscriptionMonthly() : null);
        m.put("subscription", sub);
        List<Map<String, Object>> types = new ArrayList<>();
        for (VehicleType vt : vehicleTypeService.all()) {
            TariffVehiclePrice p = t.priceOf(vt.getCode());
            Map<String, Object> e = VehicleTypeService.toMap(vt, false);
            e.put("enabled", p != null && p.isEnabled());
            e.put("firstHour", p != null ? p.getFirstHour() : null);
            e.put("extraHour", p != null ? p.getExtraHour() : null);
            e.put("subscriptionMonthly", p != null ? p.getSubscriptionMonthly() : null);
            types.add(e);
        }
        m.put("vehicleTypes", types);
        return m;
    }

    private Map<String, Object> toAdminMap(Tariff t) {
        Map<String, Object> m = toPublicMap(t);
        m.put("note", t.getNote());
        m.put("createdByUsername", t.getCreatedByUsername());
        m.put("createdAt", t.getCreatedAt() != null ? ISO.format(t.getCreatedAt()) : null);
        m.put("activatedAt", t.getActivatedAt() != null ? ISO.format(t.getActivatedAt()) : null);
        m.put("archivedAt", t.getArchivedAt() != null ? ISO.format(t.getArchivedAt()) : null);
        return m;
    }

    private static Map<String, Object> priceMap(Double first, Double extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstHour", first);
        m.put("extraHour", extra);
        return m;
    }

    // --- admin operations ---

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return tariffRepository.findAllByOrderByIdDesc().stream().map(this::toAdminMap).toList();
    }

    @Transactional
    public Map<String, Object> createDraft(TariffRequest req, User admin) {
        Tariff t = new Tariff();
        t.setStatus(TariffStatus.DRAFT);
        apply(t, req);
        t.setCreatedBy(admin);
        t.setCreatedByUsername(admin != null ? admin.getUsername() : "system");
        t.setCreatedAt(LocalDateTime.now(clock));
        Map<String, Object> out = toAdminMap(tariffRepository.save(t));
        auditService.log(AuditAction.TARIFF_DRAFT_CREATED, "Tariff", out.get("id"),
                "Tarife taslağı oluşturuldu (#" + out.get("id") + ")", null, out);
        return out;
    }

    @Transactional
    public Map<String, Object> updateDraft(Long id, TariffRequest req) {
        Tariff t = find(id);
        if (t.getStatus() != TariffStatus.DRAFT) {
            throw ApiException.conflict(ErrorCodes.TARIFF_STATE, "Yalnızca taslak tarife düzenlenebilir.");
        }
        Map<String, Object> before = toAdminMap(t);
        apply(t, req);
        Map<String, Object> out = toAdminMap(tariffRepository.save(t));
        auditService.log(AuditAction.TARIFF_DRAFT_UPDATED, "Tariff", id,
                "Tarife taslağı güncellendi (#" + id + ")", before, out);
        return out;
    }

    /** Sample calculations: current ACTIVE tariff versus the given one. */
    @Transactional
    public Map<String, Object> preview(Long id) {
        Tariff proposed = find(id);
        Tariff cur = current();
        TariffRules curRules = TariffRules.from(cur);
        TariffRules newRules = TariffRules.from(proposed);

        LocalDate day = LocalDate.now(clock).plusDays(1); // fixed reference day: deterministic per day
        LocalDateTime start = day.atTime(10, 0);
        record Sample(String label, LocalDateTime entry, LocalDateTime exit) {}
        List<Sample> samples = new ArrayList<>();
        for (int min : new int[]{5, 6, 30, 60, 61, 90, 120, 180, 300}) {
            samples.add(new Sample(min + " dakika", start, start.plusMinutes(min)));
        }
        samples.add(new Sample("Gece (20:00 - ertesi gün 10:00)", day.atTime(20, 0), day.plusDays(1).atTime(10, 0)));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> typeChanges = new ArrayList<>();
        for (VehicleType type : vehicleTypeService.all()) {
            TariffVehiclePrice cp = cur.priceOf(type.getCode());
            TariffVehiclePrice pp = proposed.priceOf(type.getCode());
            boolean curOn = cp != null && cp.isEnabled();
            boolean newOn = pp != null && pp.isEnabled();
            if (curOn != newOn) {
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("code", type.getCode());
                ch.put("displayName", type.getDisplayName());
                ch.put("currentEnabled", curOn);
                ch.put("proposedEnabled", newOn);
                typeChanges.add(ch);
            }
            if (!curOn && !newOn) continue;
            for (Sample s : samples) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", s.label());
                row.put("vehicleType", type.getCode());
                row.put("displayName", type.getDisplayName());
                row.put("entry", ISO.format(s.entry()));
                row.put("exit", ISO.format(s.exit()));
                Map<String, Object> c = null;
                if (curOn) {
                    FeeBreakdown a = FeeEngine.calculate(curRules, type.getCode(), s.entry(), s.exit());
                    c = new LinkedHashMap<>();
                    c.put("fee", a.fee());
                    c.put("paidMinutes", a.paidMinutes());
                }
                Map<String, Object> p = null;
                if (newOn) {
                    FeeBreakdown b = FeeEngine.calculate(newRules, type.getCode(), s.entry(), s.exit());
                    p = new LinkedHashMap<>();
                    p.put("fee", b.fee());
                    p.put("paidMinutes", b.paidMinutes());
                    p.put("segments", DaySegment.toMaps(b.segments()));
                }
                row.put("current", c);
                row.put("proposed", p);
                rows.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", toPublicMap(cur));
        result.put("proposed", toPublicMap(proposed));
        result.put("rows", rows);
        result.put("typeChanges", typeChanges);
        return result;
    }

    @Transactional
    public Map<String, Object> schedule(Long id, LocalDateTime effectiveFrom, String password, User admin) {
        if (admin == null || password == null || !passwordEncoder.matches(password, admin.getPassword())) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.PASSWORD_MISMATCH, "Yönetici şifresi doğrulanamadı.");
        }
        Tariff t = find(id);
        if (t.getStatus() != TariffStatus.DRAFT) {
            throw ApiException.conflict(ErrorCodes.TARIFF_STATE, "Yalnızca taslak tarife zamanlanabilir.");
        }
        if (effectiveFrom == null || !effectiveFrom.isAfter(LocalDateTime.now(clock))) {
            throw ApiException.badRequest(ErrorCodes.INVALID_TARIFF, "Yürürlük zamanı gelecekte olmalıdır.");
        }
        if (tariffRepository.findFirstByStatus(TariffStatus.SCHEDULED).isPresent()) {
            throw ApiException.conflict(ErrorCodes.TARIFF_STATE,
                    "Zaten zamanlanmış bir tarife var; önce iptal edin.");
        }
        validate(t);
        t.setStatus(TariffStatus.SCHEDULED);
        t.setEffectiveFrom(effectiveFrom);
        Tariff saved = tariffRepository.saveAndFlush(t);

        refreshCache();
        scheduleTimer(saved);
        changePublisher.globalEvent(SyncEventType.TARIFF_UPDATED, currentWithUpcoming());
        Map<String, Object> out = toAdminMap(saved);
        auditService.log(AuditAction.TARIFF_SCHEDULED, "Tariff", id,
                "Tarife zamanlandı (#" + id + ", yürürlük " + ISO.format(effectiveFrom) + ")", null, out);
        return out;
    }

    @Transactional
    public Map<String, Object> cancel(Long id) {
        Tariff t = find(id);
        if (t.getStatus() != TariffStatus.DRAFT && t.getStatus() != TariffStatus.SCHEDULED) {
            throw ApiException.conflict(ErrorCodes.TARIFF_STATE,
                    "Yalnızca taslak veya zamanlanmış tarife iptal edilebilir.");
        }
        boolean wasScheduled = t.getStatus() == TariffStatus.SCHEDULED;
        t.setStatus(TariffStatus.ARCHIVED);
        t.setArchivedAt(LocalDateTime.now(clock));
        Tariff saved = tariffRepository.saveAndFlush(t);
        if (wasScheduled) {
            cancelTimer();
            refreshCache();
            changePublisher.globalEvent(SyncEventType.TARIFF_UPDATED, currentWithUpcoming());
        }
        Map<String, Object> out = toAdminMap(saved);
        auditService.log(AuditAction.TARIFF_CANCELLED, "Tariff", id,
                (wasScheduled ? "Zamanlanmış" : "Taslak") + " tarife iptal edildi (#" + id + ")", null, out);
        return out;
    }

    // --- activation ---

    /** SCHEDULED -> ACTIVE (old ACTIVE -> ARCHIVED). Safe to call repeatedly. */
    public synchronized void activateDue() {
        Boolean done = tx.execute(status -> {
            Tariff next = tariffRepository.findFirstByStatus(TariffStatus.SCHEDULED).orElse(null);
            if (next == null || !isDue(next)) {
                refreshCache(); // drop a stale cached copy so current() stops retrying
                return false;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            Tariff old = tariffRepository.findFirstByStatus(TariffStatus.ACTIVE).orElse(null);
            if (old != null) {
                old.setStatus(TariffStatus.ARCHIVED);
                old.setArchivedAt(now);
                tariffRepository.saveAndFlush(old); // free the ACTIVE slot before the next one takes it
            }
            next.setStatus(TariffStatus.ACTIVE);
            next.setActivatedAt(now);
            tariffRepository.saveAndFlush(next);
            refreshCache();
            VehicleTypeService.Change change = vehicleTypeService.syncActiveFrom(next);
            changePublisher.globalEvent(SyncEventType.TARIFF_UPDATED, currentWithUpcoming());
            if (!change.isEmpty()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("vehicleTypes", vehicleTypeService.activePublicList());
                changePublisher.globalEvent(SyncEventType.VEHICLE_TYPES_UPDATED, payload);
            }
            String desc = "Zamanlanmış tarife yürürlüğe girdi (#" + next.getId() + ")";
            if (!change.enabled().isEmpty()) desc += "; etkinleşen: " + String.join(", ", change.enabled());
            if (!change.disabled().isEmpty()) desc += "; pasifleşen: " + String.join(", ", change.disabled());
            auditService.logSystem(AuditAction.TARIFF_ACTIVATED, "Tariff", next.getId(),
                    desc, null, toPublicMap(next));
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            cancelTimer();
            log.info("Scheduled tariff activated: id={}", active != null ? active.getId() : null);
        }
    }

    private boolean isDue(Tariff t) {
        return t.getEffectiveFrom() != null && !t.getEffectiveFrom().isAfter(LocalDateTime.now(clock));
    }

    private void refreshCache() {
        active = tariffRepository.findFirstByStatus(TariffStatus.ACTIVE).orElse(null);
        scheduled = tariffRepository.findFirstByStatus(TariffStatus.SCHEDULED).orElse(null);
    }

    private synchronized void scheduleTimer(Tariff t) {
        cancelTimer();
        if (t.getEffectiveFrom() == null) return;
        timer = taskScheduler.schedule(() -> {
            try {
                activateDue();
            } catch (RuntimeException e) {
                log.error("Scheduled tariff activation failed", e);
            }
        }, t.getEffectiveFrom().atZone(clock.getZone()).toInstant());
    }

    private synchronized void cancelTimer() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    // --- validation ---

    private Tariff find(Long id) {
        return tariffRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Tarife bulunamadı: " + id));
    }

    private void apply(Tariff t, TariffRequest r) {
        t.setFreeMinutes(r.getFreeMinutes());
        t.setPaidStart(parseTime(r.getPaidStart(), "Ücretli saat başlangıcı"));
        t.setPaidEnd(parseTime(r.getPaidEnd(), "Ücretli saat bitişi"));
        t.setNote(r.getNote());
        applyPrices(t, r);
        validate(t);
    }

    /** Builds the price rows: from the request, or from the legacy fields; missing types copy the ACTIVE tariff. */
    private void applyPrices(Tariff t, TariffRequest r) {
        Map<String, TariffVehiclePrice> wanted = new LinkedHashMap<>();
        if (r.getVehicleTypes() != null && !r.getVehicleTypes().isEmpty()) {
            for (VehiclePriceRequest v : r.getVehicleTypes()) {
                String code = v.getCode() == null ? "" : v.getCode().trim().toUpperCase(Locale.ROOT);
                if (vehicleTypeService.find(code) == null) {
                    throw invalid("Bilinmeyen araç tipi: " + v.getCode());
                }
                wanted.put(code, new TariffVehiclePrice(t, code, v.getEnabled() == null || v.getEnabled(),
                        v.getFirstHour(), v.getExtraHour(), v.getSubscriptionMonthly()));
            }
        } else {
            wanted.put(VehicleTypes.SMALL, new TariffVehiclePrice(t, VehicleTypes.SMALL, true,
                    r.getSmallFirstHour(), r.getSmallExtraHour(), r.getSubscriptionSmallMonthly()));
            wanted.put(VehicleTypes.LARGE, new TariffVehiclePrice(t, VehicleTypes.LARGE, true,
                    r.getLargeFirstHour(), r.getLargeExtraHour(), r.getSubscriptionLargeMonthly()));
        }
        Tariff act = active;
        if (act != null) {
            for (TariffVehiclePrice p : act.getPrices()) {
                wanted.computeIfAbsent(p.getVehicleType(), c -> new TariffVehiclePrice(t, c, p.isEnabled(),
                        p.getFirstHour(), p.getExtraHour(), p.getSubscriptionMonthly()));
            }
        }
        // update in place (a removed + re-added row with the same key would collide on flush)
        t.getPrices().removeIf(p -> !wanted.containsKey(p.getVehicleType()));
        for (TariffVehiclePrice w : wanted.values()) {
            TariffVehiclePrice existing = t.priceOf(w.getVehicleType());
            if (existing == null) {
                t.getPrices().add(w);
            } else {
                existing.setEnabled(w.isEnabled());
                existing.setFirstHour(w.getFirstHour());
                existing.setExtraHour(w.getExtraHour());
                existing.setSubscriptionMonthly(w.getSubscriptionMonthly());
            }
        }
    }

    private LocalTime parseTime(String s, String what) {
        if (s == null || s.isBlank()) throw invalid(what + " boş olamaz.");
        try {
            return LocalTime.parse(s.trim(), HHMM);
        } catch (DateTimeParseException e) {
            throw invalid(what + " SS:dd biçiminde olmalıdır.");
        }
    }

    /** Throws 400 INVALID_TARIFF on any bad field. */
    private void validate(Tariff t) {
        boolean anyEnabled = false;
        for (TariffVehiclePrice p : t.getPrices()) {
            if (!p.isEnabled()) continue;
            anyEnabled = true;
            String name = vehicleTypeService.displayName(p.getVehicleType());
            requirePositive(p.getFirstHour(), name + " ilk saat ücreti");
            requireNonNegative(p.getExtraHour(), name + " ek saat ücreti");
            requirePositive(p.getSubscriptionMonthly(), name + " abonelik ücreti");
        }
        if (!anyEnabled) throw invalid("En az bir araç tipi etkin olmalıdır.");
        if (t.getFreeMinutes() == null || t.getFreeMinutes() < 0 || t.getFreeMinutes() > 120) {
            throw invalid("Ücretsiz süre 0 ile 120 dakika arasında olmalıdır.");
        }
        LocalTime start = t.getPaidStart();
        LocalTime end = t.getPaidEnd();
        if (start == null || end == null) throw invalid("Ücretli saat aralığı eksik.");
        // 00:00 as the end means midnight (end of day)
        if (!LocalTime.MIDNIGHT.equals(end) && !start.isBefore(end)) {
            throw invalid("Ücretli saat başlangıcı bitişten önce olmalıdır (bitiş 00:00 gece yarısıdır).");
        }
    }

    private void requirePositive(Double v, String what) {
        if (v == null || v.isNaN() || v.isInfinite() || v <= 0) throw invalid(what + " sıfırdan büyük olmalıdır.");
    }

    private void requireNonNegative(Double v, String what) {
        if (v == null || v.isNaN() || v.isInfinite() || v < 0) throw invalid(what + " negatif olamaz.");
    }

    private ApiException invalid(String msg) {
        return ApiException.badRequest(ErrorCodes.INVALID_TARIFF, msg);
    }

    /** Same values as the Flyway V3 seed (the tariff hard-coded before stage 3). */
    private Tariff defaultTariff() {
        LocalDateTime now = LocalDateTime.now(clock);
        Tariff t = new Tariff();
        t.setStatus(TariffStatus.ACTIVE);
        t.getPrices().add(new TariffVehiclePrice(t, VehicleTypes.SMALL, true, 25.0, 15.0, 2500.0));
        t.getPrices().add(new TariffVehiclePrice(t, VehicleTypes.LARGE, true, 50.0, 30.0, 3000.0));
        t.setFreeMinutes(5);
        t.setPaidStart(LocalTime.of(8, 0));
        t.setPaidEnd(LocalTime.of(22, 0));
        t.setEffectiveFrom(now);
        t.setActivatedAt(now);
        t.setCreatedByUsername("system");
        t.setCreatedAt(now);
        t.setNote("Varsayılan tarife");
        return t;
    }
}
