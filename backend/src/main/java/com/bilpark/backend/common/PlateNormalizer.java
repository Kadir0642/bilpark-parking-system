package com.bilpark.backend.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-side licence plate normalization.
 * "34 abc-123" -> "34ABC123". Every plate is stored and compared in this form,
 * so an active plate is unique across all zones.
 *
 * Two rules exist: the Turkish plate rule (default) and the foreign / special plate rule, used only when the
 * officer explicitly marks the entry as foreign (check-in foreignPlate=true). Both store the same cleaned form,
 * so lookups (check-out, runaway, citizen page) do not need to know which rule created the row.
 * A country field may be added later next to the flag; nothing here assumes there is none.
 */
public final class PlateNormalizer {
    private PlateNormalizer() {}

    // İl kodu 01-81, ardından: 1 harf + 4-5 rakam | 2 harf + 3-4 rakam | 3 harf + 2-3 rakam
    private static final Pattern TR_PLATE = Pattern.compile(
            "^(0[1-9]|[1-7][0-9]|8[01])([A-Z][0-9]{4,5}|[A-Z]{2}[0-9]{3,4}|[A-Z]{3}[0-9]{2,3})$");

    /** Foreign / special plate: after removing spaces and dashes, A-Z and 0-9 only, 2-12 characters. */
    public static final int FOREIGN_MIN = 2;
    public static final int FOREIGN_MAX = 12;
    private static final Pattern FOREIGN_PLATE = Pattern.compile("^[A-Z0-9]{" + FOREIGN_MIN + "," + FOREIGN_MAX + "}$");

    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]+");

    /** Strips spaces/dashes and upper-cases; does not validate. Returns "" for null. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = SEPARATORS.matcher(raw).replaceAll("");
        // Türkçe klavyeden gelen i/ı/İ harflerini plaka alfabesine çevir
        s = s.replace('İ', 'I').replace('ı', 'I').replace('i', 'I');
        return s.toUpperCase(Locale.ROOT);
    }

    /** Turkish plate rule on an already cleaned value. */
    public static boolean isValid(String normalized) {
        return normalized != null && TR_PLATE.matcher(normalized).matches();
    }

    /** Foreign / special plate rule on an already cleaned value (A-Z0-9, 2-12). Every TR plate also passes it. */
    public static boolean isValidForeign(String normalized) {
        return normalized != null && FOREIGN_PLATE.matcher(normalized).matches();
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

    /** Cleans and validates with the TR rule; throws 400 INVALID_PLATE with a readable Turkish message. */
    public static String normalize(String raw) {
        return normalize(raw, false);
    }

    /**
     * Cleans and validates: foreign=false uses the TR rule, foreign=true the foreign / special rule.
     * Throws 400 INVALID_PLATE with a readable Turkish message.
     */
    public static String normalize(String raw, boolean foreign) {
        String plate = clean(raw);
        String shown = raw == null ? "" : raw.trim();
        if (foreign) {
            if (!isValidForeign(plate)) {
                throw ApiException.badRequest(ErrorCodes.INVALID_PLATE,
                        "Geçersiz yabancı / özel plaka: \"" + shown + "\". Yalnızca A-Z harfleri ve rakam, "
                                + FOREIGN_MIN + "-" + FOREIGN_MAX + " karakter (boşluk ve tire sayılmaz).");
            }
            return plate;
        }
        if (!isValid(plate)) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PLATE,
                    "Geçersiz plaka: \"" + shown + "\". Örnek: 34 ABC 123");
        }
        return plate;
    }

    /**
     * Read-only queries that must find both TR and foreign plates (citizen debt page / QR): cleans and checks the
     * foreign rule, which every TR plate also satisfies.
     */
    public static String normalizeAny(String raw) {
        return normalize(raw, true);
    }
}
