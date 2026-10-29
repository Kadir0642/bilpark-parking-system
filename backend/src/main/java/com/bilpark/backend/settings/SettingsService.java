package com.bilpark.backend.settings;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AppSetting;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.repository.AppSettingRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Admin settings stored in app_settings:
 *   performance.weights   JSON PerformanceWeights (personnel report score)
 *   rent.annual.<year>    yearly total rent paid to the municipality (3 streets, one tender), TL
 *   retention.last-run    JSON summary of the last retention job (written by the job, not audited)
 * Missing keys fall back to the defaults below. Admin changes are audited (SETTINGS_UPDATED).
 */
@Service
public class SettingsService {

    public static final String KEY_PERFORMANCE_WEIGHTS = "performance.weights";
    public static final String KEY_RENT_PREFIX = "rent.annual.";
    public static final String KEY_RETENTION_LAST_RUN = "retention.last-run";
    public static final String KEY_LAST_APP_VERSION = "app.last-version";

    /**
     * Score weights in percent (sum must be 100) plus the punctuality rule: a work day counts as
     * "on time" when its first shift started at or before startTime + graceMinutes.
     */
    public record PerformanceWeights(int hours, int punctuality, int throughput, int collection,
                                     int runaway, int cancellation, String startTime, int graceMinutes) {
        public static final PerformanceWeights DEFAULT = new PerformanceWeights(20, 15, 20, 20, 15, 10, "08:00", 5);

        public int sum() {
            return hours + punctuality + throughput + collection + runaway + cancellation;
        }

        public LocalTime startLocalTime() {
            return LocalTime.parse(startTime);
        }
    }

    private final AppSettingRepository repository;
    private final AuditService auditService;
    private final JsonMapper mapper;
    private final Clock clock;

    public SettingsService(AppSettingRepository repository, AuditService auditService, JsonMapper mapper, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---- generic ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return repository.findById(key).map(AppSetting::getValue);
    }

    /** Stores a value without auditing (callers audit with their own context). */
    @Transactional
    public void put(String key, String value) {
        AppSetting s = repository.findById(key).orElseGet(AppSetting::new);
        s.setKey(key);
        s.setValue(value);
        s.setUpdatedAt(LocalDateTime.now(clock));
        s.setUpdatedBy(currentUsername());
        repository.save(s);
    }

    // ---- performance weights ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PerformanceWeights getPerformanceWeights() {
        return get(KEY_PERFORMANCE_WEIGHTS).map(v -> {
            try {
                return mapper.readValue(v, PerformanceWeights.class);
            } catch (RuntimeException e) {
                return PerformanceWeights.DEFAULT;
            }
        }).orElse(PerformanceWeights.DEFAULT);
    }

    @Transactional
    public PerformanceWeights updatePerformanceWeights(PerformanceWeights w) {
        validate(w);
        PerformanceWeights before = getPerformanceWeights();
        put(KEY_PERFORMANCE_WEIGHTS, mapper.writeValueAsString(w));
        auditService.log(AuditAction.SETTINGS_UPDATED, "Setting", KEY_PERFORMANCE_WEIGHTS,
                "Performans puanı ağırlıkları güncellendi", before, w);
        return w;
    }

    static void validate(PerformanceWeights w) {
        if (w == null) throw invalid("Ağırlıklar gerekli.");
        int[] all = {w.hours(), w.punctuality(), w.throughput(), w.collection(), w.runaway(), w.cancellation()};
        for (int v : all) {
            if (v < 0 || v > 100) throw invalid("Her ağırlık 0-100 arasında olmalı.");
        }
        if (w.sum() != 100) throw invalid("Ağırlıkların toplamı 100 olmalı (şu an " + w.sum() + ").");
        if (w.startTime() == null) throw invalid("Mesai başlangıç saati gerekli (SS:dd).");
        try {
            LocalTime.parse(w.startTime());
        } catch (DateTimeParseException e) {
            throw invalid("Mesai başlangıç saati SS:dd biçiminde olmalı.");
        }
        if (w.graceMinutes() < 0 || w.graceMinutes() > 120) throw invalid("Gecikme toleransı 0-120 dakika olmalı.");
    }

    // ---- annual rent --------------------------------------------------------------------------

    /** Yearly total rent of that year in TL; 0 when not entered. */
    @Transactional(readOnly = true)
    public double annualRent(int year) {
        return get(KEY_RENT_PREFIX + year).map(Double::parseDouble).orElse(0.0);
    }

    /** All entered years, ascending. */
    @Transactional(readOnly = true)
    public Map<Integer, Double> annualRents() {
        Map<Integer, Double> out = new TreeMap<>();
        for (AppSetting s : repository.findByKeyStartingWith(KEY_RENT_PREFIX)) {
            try {
                out.put(Integer.parseInt(s.getKey().substring(KEY_RENT_PREFIX.length())), Double.parseDouble(s.getValue()));
            } catch (NumberFormatException ignored) {
                // not a rent row
            }
        }
        return out;
    }

    @Transactional
    public Map<String, Object> setAnnualRent(int year, double amount) {
        if (year < 2000 || year > 2100) throw invalid("Yıl 2000-2100 arasında olmalı.");
        if (!(amount >= 0) || amount > 1_000_000_000d) throw invalid("Kira bedeli 0 veya pozitif olmalı.");
        Double before = get(KEY_RENT_PREFIX + year).map(Double::parseDouble).orElse(null);
        put(KEY_RENT_PREFIX + year, Double.toString(amount));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("year", year);
        after.put("annualRent", amount);
        after.put("monthlyShare", amount / 12.0);
        Map<String, Object> beforeMap = new LinkedHashMap<>();
        beforeMap.put("year", year);
        beforeMap.put("annualRent", before);
        auditService.log(AuditAction.SETTINGS_UPDATED, "Setting", KEY_RENT_PREFIX + year,
                year + " yılı kira bedeli güncellendi", beforeMap, after);
        return after;
    }

    private static ApiException invalid(String msg) {
        return ApiException.badRequest(ErrorCodes.INVALID_SETTINGS, msg);
    }

    private static String currentUsername() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && !"anonymousUser".equals(a.getName()) ? a.getName() : AuditService.SYSTEM;
    }
}
