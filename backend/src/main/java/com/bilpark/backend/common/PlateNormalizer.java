package com.bilpark.backend.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-side licence plate normalization.
 * "34 abc-123" -> "34ABC123". Every plate is stored and compared in this form,
 * so an active plate is unique across all zones.
 */
public final class PlateNormalizer {
    private PlateNormalizer() {}

    // İl kodu 01-81, ardından: 1 harf + 4-5 rakam | 2 harf + 3-4 rakam | 3 harf + 2-3 rakam
    private static final Pattern TR_PLATE = Pattern.compile(
            "^(0[1-9]|[1-7][0-9]|8[01])([A-Z][0-9]{4,5}|[A-Z]{2}[0-9]{3,4}|[A-Z]{3}[0-9]{2,3})$");

    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]+");

    /** Strips spaces/dashes and upper-cases; does not validate. Returns "" for null. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = SEPARATORS.matcher(raw).replaceAll("");
        // Türkçe klavyeden gelen i/ı/İ harflerini plaka alfabesine çevir
        s = s.replace('İ', 'I').replace('ı', 'I').replace('i', 'I');
        return s.toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String normalized) {
        return normalized != null && TR_PLATE.matcher(normalized).matches();
    }

    /**
     * Cleans without format validation, for closing an existing record (check-out, runaway).
     * Rows created before validation existed may not match the regex but must still be closable.
     */
    public static String forLookup(String raw) {
        String plate = clean(raw);
        if (plate.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PLATE, "Plaka boş olamaz.");
        }
        return plate;
    }

    /** Cleans and validates; throws 400 INVALID_PLATE with a readable Turkish message. */
    public static String normalize(String raw) {
        String plate = clean(raw);
        if (!isValid(plate)) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PLATE,
                    "Geçersiz plaka: \"" + (raw == null ? "" : raw.trim()) + "\". Örnek: 34 ABC 123");
        }
        return plate;
    }
}
