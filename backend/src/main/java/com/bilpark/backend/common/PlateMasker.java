package com.bilpark.backend.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plate masking for everything that goes to the developer role (KVKK, data minimisation).
 * One place for the rule, see docs/api-gelistirici-izleme.md "Plaka maskeleme".
 *
 * maskPlate("34 ABC 123") = "34 *** *23": the first 2 and the last 2 characters stay, letters and
 * digits in between become '*', separators (space, dash) stay. Short values (foreign / special plates can
 * have 2-12 characters): 4-6 characters keep only the first and the last one ("CD12" = "C**2"), shorter
 * values are masked completely. A Turkish plate always has 7-9 characters, so its mask is unchanged.
 *
 * Foreign / special plates inside free text: an upper-case token of A-Z and 0-9 (2-12 characters, at least
 * one letter and one digit, standing alone between spaces or punctuation) is masked as well. A letter-only
 * foreign plate cannot be told apart from a word in free text; where the plate position is known (the
 * leading plate of vehicle / subscription audit summaries, "plate" keys in JSON) it is masked anyway.
 */
public final class PlateMasker {
    private PlateMasker() {}

    private static final int KEEP = 2;
    private static final int SHORT_MAX = 6; // 4-6 characters: keep 1 + 1

    // A plate-shaped token inside free text: province code, optional separator, 1-3 letters, optional
    // separator, 2-5 digits, not glued to other letters/digits. The candidate is then checked with the
    // real TR plate rule (the same one PlateNormalizer / mobile core/plate.dart use).
    private static final Pattern IN_TEXT = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{2})([ -]?)([A-Za-zİı]{1,3})([ -]?)(\\d{2,5})(?![\\p{L}\\p{N}])");

    // A foreign / special plate token in free text (see the class comment). Leading and trailing context is
    // limited to spaces and punctuation, so ISO times ("05T10" in 2026-11-05T10:15) and ids stay readable.
    private static final Pattern FOREIGN_IN_TEXT = Pattern.compile(
            "(?<=^|[\\s(\\[{\"'=,;/])([A-Z0-9]{2,12})(?=$|[\\s)\\]}\"',;:.!?])");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("[0-9]");

    // Personal-data keys in audit before/after JSON that the developer must not see at all
    private static final Set<String> HIDDEN_KEYS = Set.of(
            "fullname", "ownername", "ownerphone", "phone", "password", "newpassword");

    // JSON keys whose string value is a plate (TR or foreign): masked whatever its shape
    private static final Set<String> PLATE_KEYS = Set.of("plate", "licenseplate", "currentplate");

    public static String maskPlate(String plate) {
        if (plate == null) return null;
        int n = plate.length();
        if (n < 2 * KEEP) return "*".repeat(n);
        int keep = n <= SHORT_MAX ? 1 : KEEP;
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = plate.charAt(i);
            boolean edge = i < keep || i >= n - keep;
            sb.append(edge || !Character.isLetterOrDigit(c) ? c : '*');
        }
        return sb.toString();
    }

    /**
     * Masks every plate-shaped part of a free text ("34ABC123 giriş: Saha 1" becomes "34****23 giriş: Saha 1",
     * "MAB1234 giriş" becomes "MA***34 giriş").
     */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = IN_TEXT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String candidate = m.group();
            String replacement = PlateNormalizer.isValid(PlateNormalizer.clean(candidate))
                    ? maskPlate(candidate) : candidate;
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return maskForeignTokens(out.toString());
    }

    private static String maskForeignTokens(String text) {
        Matcher m = FOREIGN_IN_TEXT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            boolean plateLike = HAS_LETTER.matcher(token).find() && HAS_DIGIT.matcher(token).find();
            m.appendReplacement(out, Matcher.quoteReplacement(plateLike ? maskPlate(token) : token));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Audit summary whose first word is known to be a plate (vehicle and subscription actions write
     * "PLATE giriş: ..."): that word is masked whatever its shape (a letter-only foreign plate too), the rest
     * goes through {@link #maskText}.
     */
    public static String maskLeadingPlate(String summary) {
        if (summary == null || summary.isEmpty()) return summary;
        int space = summary.indexOf(' ');
        String first = space < 0 ? summary : summary.substring(0, space);
        String rest = space < 0 ? "" : summary.substring(space);
        return maskPlate(first) + maskText(rest);
    }

    /** Walks parsed JSON (Map / List / String ...): masks plates in every string, hides personal-data keys. */
    public static Object maskJson(Object value) {
        if (value instanceof String s) return maskText(s);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String lower = key.toLowerCase(Locale.ROOT);
                Object v = e.getValue();
                if (HIDDEN_KEYS.contains(lower) && v != null) out.put(key, "***");
                else if (PLATE_KEYS.contains(lower) && v instanceof String s) out.put(key, maskPlate(s));
                else out.put(key, maskJson(v));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(maskJson(o));
            return out;
        }
        return value;
    }
}
