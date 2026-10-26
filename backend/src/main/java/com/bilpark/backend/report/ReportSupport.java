package com.bilpark.backend.report;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.Street;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.repository.StreetRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/** Parameter parsing and street display names shared by the report services. */
@Component
public class ReportSupport {

    public static final int MAX_RANGE_DAYS = 366;
    static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Inclusive day range; the queries use [from 00:00, to+1 00:00). */
    public record Range(LocalDate from, LocalDate to) {
        public LocalDateTime start() { return from.atStartOfDay(); }
        public LocalDateTime end() { return to.plusDays(1).atStartOfDay(); }
        public String display() { return DISPLAY_DATE.format(from) + " – " + DISPLAY_DATE.format(to); }
    }

    private final StreetRepository streets;

    public ReportSupport(StreetRepository streets) {
        this.streets = streets;
    }

    public static Range range(String from, String to) {
        LocalDate f = date("from", from);
        LocalDate t = date("to", to);
        if (t.isBefore(f)) throw bad("Bitiş tarihi başlangıçtan önce olamaz.");
        if (f.plusDays(MAX_RANGE_DAYS).isBefore(t)) {
            throw bad("Tarih aralığı en fazla " + MAX_RANGE_DAYS + " gün olabilir.");
        }
        return new Range(f, t);
    }

    public static YearMonth month(String month) {
        if (month == null || month.isBlank()) throw bad("Ay (month=yyyy-MM) zorunludur.");
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw bad("Ay yyyy-MM biçiminde olmalı (örn. 2026-10).");
        }
    }

    private static LocalDate date(String name, String v) {
        if (v == null || v.isBlank()) throw bad("'" + name + "' tarihi zorunludur (yyyy-MM-dd).");
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw bad("'" + name + "' tarihi yyyy-MM-dd biçiminde olmalı.");
        }
    }

    public static ApiException bad(String message) {
        return ApiException.badRequest(ErrorCodes.VALIDATION_ERROR, message);
    }

    /** StreetLocation name -> display name, from the streets table (fallback: the enum). */
    public Map<String, String> streetNames() {
        Map<String, String> m = new HashMap<>();
        for (StreetLocation l : StreetLocation.values()) m.put(l.name(), l.getDisplayName());
        for (Street s : streets.findAll()) m.put(s.getCode(), s.getDisplayName());
        return m;
    }

    public static String street(Map<String, String> names, StreetLocation l) {
        return l == null ? null : names.getOrDefault(l.name(), l.getDisplayName());
    }
}
