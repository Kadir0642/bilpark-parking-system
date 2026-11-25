package com.bilpark.backend.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plate masking for everything that goes to the developer role (KVKK, data minimisation).
 * One place for the rule, see docs/api-gelistirici-izleme.md "Plaka maskeleme".
 *
 * maskPlate("34 ABC 123") = "34 *** *23": the first 2 and the last 2 characters stay, letters and
 * digits in between become '*', separators (space, dash) stay. Values shorter than 4 characters are
 * masked completely.
 */
public final class PlateMasker {
    private PlateMasker() {}

    private static final int KEEP = 2;

    // A plate-shaped token inside free text: province code, optional separator, 1-3 letters, optional
    // separator, 2-5 digits, not glued to other letters/digits. The candidate is then checked with the
    // real TR plate rule (the same one PlateNormalizer / mobile core/plate.dart use).
    private static final Pattern IN_TEXT = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{2})([ -]?)([A-Za-z\u0130\u0131]{1,3})([ -]?)(\\d{2,5})(?![\\p{L}\\p{N}])");

    // Personal-data keys in audit before/after JSON that the developer must not see at all
    private static final Set<String> HIDDEN_KEYS = Set.of(
            "fullname", "ownername", "ownerphone", "phone", "password", "newpassword");

    public static String maskPlate(String plate) {
        if (plate == null) return null;
        int n = plate.length();
        if (n < 2 * KEEP) return "*".repeat(n);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = plate.charAt(i);
            boolean edge = i < KEEP || i >= n - KEEP;
            sb.append(edge || !Character.isLetterOrDigit(c) ? c : '*');
        }
        return sb.toString();
    }

    /** Masks every plate-shaped part of a free text ("34ABC123 giriş: Saha 1" becomes "34****23 giriş: Saha 1"). */
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
        return out.toString();
    }

    /** Walks parsed JSON (Map / List / String ...): masks plates in every string, hides personal-data keys. */
    public static Object maskJson(Object value) {
        if (value instanceof String s) return maskText(s);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, HIDDEN_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT)) && e.getValue() != null
                        ? "***" : maskJson(e.getValue()));
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
