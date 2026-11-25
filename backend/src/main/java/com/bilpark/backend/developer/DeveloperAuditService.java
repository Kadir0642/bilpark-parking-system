package com.bilpark.backend.developer;

import com.bilpark.backend.audit.AuditQueryService;
import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.common.PlateMasker;
import com.bilpark.backend.common.PlateNormalizer;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.AuditLog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only audit log for the developer: the admin's list (same filters, same page shape) with every plate
 * masked and no entityId filter. Viewing the first page is itself audited (DEV_AUDIT_VIEWED).
 */
@Service
public class DeveloperAuditService {

    /** Query string values as they arrive (entityId is deliberately absent). */
    public record Query(String from, String to, String action, String actor, String actorRole,
                        String entityType, String q) {}

    /**
     * A resolved developer search: {@code plate} is matched only as a whole word of the summary (exact plate
     * token), {@code text} only against the summary as the developer sees it (plates masked). Either may be null.
     */
    record Search(String plate, String text) {
        /** Value for the SQL pre-filter (LIKE %value%): a superset of the rows the Java check keeps. */
        String prefilter() {
            return text != null ? text : plate;
        }
    }

    private static final int MIN_TEXT_LENGTH = 4;

    /** A search reads at most this many rows of the date window before filtering (newest first). */
    static final int SEARCH_SCAN_LIMIT = 5000;

    /** Actions whose summary starts with the plate ("PLATE giriş: ..."), see ParkingService / SubscriptionService. */
    private static final Set<AuditAction> LEADING_PLATE_ACTIONS = EnumSet.of(
            AuditAction.VEHICLE_CHECK_IN, AuditAction.VEHICLE_CHECK_OUT, AuditAction.VEHICLE_RUNAWAY,
            AuditAction.RECORD_CANCELLED, AuditAction.SPOT_CANCELLED,
            AuditAction.SUBSCRIPTION_CREATED, AuditAction.SUBSCRIPTION_CANCELLED);

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern SEPARATOR = Pattern.compile("[\\s-]");

    private final AuditQueryService queries;
    private final AuditService auditService;

    public DeveloperAuditService(AuditQueryService queries, AuditService auditService) {
        this.queries = queries;
        this.auditService = auditService;
    }

    public List<Map<String, String>> actions() {
        return queries.actions();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> list(Query query, int page, int size) {
        Search search = resolveSearch(query.q());
        AuditQueryService.Filter filter = new AuditQueryService.Filter(query.from(), query.to(), query.action(),
                query.actor(), query.actorRole(), query.entityType(), null,
                search == null ? null : search.prefilter(), true);

        Map<String, Object> body;
        if (search == null) {
            body = queries.list(filter, page, size);
            List<Map<String, Object>> masked = new ArrayList<>();
            for (Map<String, Object> row : (List<Map<String, Object>>) body.get("content")) masked.add(mask(row));
            body.put("content", masked);
        } else {
            body = searchPage(filter, search, page, size);
        }

        if (Math.max(0, page) == 0) {
            auditService.log(AuditAction.DEV_AUDIT_VIEWED, "System", "audit", viewedSummary(query, search), null, null);
        }
        return body;
    }

    /** Search path: SQL pre-filter, then the exact plate-token / masked-text check in Java, then paging. */
    private Map<String, Object> searchPage(AuditQueryService.Filter filter, Search search, int page, int size) {
        int s = Math.max(1, Math.min(size, 200));
        int p = Math.max(0, page);
        List<AuditLog> scanned = queries.scan(filter, SEARCH_SCAN_LIMIT);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (AuditLog a : scanned) {
            Map<String, Object> masked = mask(queries.toMap(a));
            if (matches(search, a.getSummary(), (String) masked.get("summary"))) hits.add(masked);
        }
        int total = hits.size();
        int from = Math.min(total, p * s);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", new ArrayList<>(hits.subList(from, Math.min(total, from + s))));
        body.put("page", p);
        body.put("size", s);
        body.put("totalElements", (long) total);
        body.put("totalPages", (total + s - 1) / s);
        body.put("maintenanceHidden", 0L);
        body.put("searchLimited", scanned.size() >= SEARCH_SCAN_LIMIT);
        return body;
    }

    /** True when the stored summary has the plate as a whole word, or the masked summary contains the text. */
    static boolean matches(Search search, String summary, String maskedSummary) {
        if (summary == null) return false;
        if (search.plate() != null) {
            for (String word : NON_WORD.split(summary)) {
                if (!word.isEmpty() && PlateNormalizer.clean(word).equals(search.plate())) return true;
            }
        }
        if (search.text() != null && maskedSummary != null) {
            return maskedSummary.toLowerCase(Locale.ROOT).contains(search.text().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private static Map<String, Object> mask(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.put("entityId", PlateMasker.maskText((String) row.get("entityId")));
        m.put("summary", maskSummary((String) row.get("action"), (String) row.get("summary")));
        m.put("before", PlateMasker.maskJson(row.get("before")));
        m.put("after", PlateMasker.maskJson(row.get("after")));
        return m;
    }

    static String maskSummary(String action, String summary) {
        AuditAction a = null;
        try {
            a = action == null ? null : AuditAction.valueOf(action);
        } catch (IllegalArgumentException ignored) {
            // unknown action name: plain text masking
        }
        return a != null && LEADING_PLATE_ACTIONS.contains(a)
                ? PlateMasker.maskLeadingPlate(summary) : PlateMasker.maskText(summary);
    }

    /**
     * `q` is accepted in two forms (docs/api-gelistirici-izleme.md "Arama"):
     * <ul>
     *   <li>a full plate, TR or foreign / special (any spelling; after removing spaces and dashes A-Z and 0-9,
     *       2-12 characters). It matches only a whole word of the summary that is exactly that plate, so a
     *       piece of a plate ("34ABC12", "MAB1") never finds a longer plate.</li>
     *   <li>free text without any digit, at least 4 characters. It is matched against the summary as the
     *       developer sees it, with every plate already masked, so it can never match inside a plate word.
     *       A digit-free text without separators that is also a valid foreign plate shape ("TAXI") is
     *       additionally tried as an exact plate word.</li>
     * </ul>
     * Everything else (digits mixed with text that is not a plate, too short text) is refused.
     */
    static Search resolveSearch(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = PlateNormalizer.clean(raw);
        boolean hasDigit = raw.chars().anyMatch(Character::isDigit);
        boolean plateShape = PlateNormalizer.isValidForeign(cleaned); // every TR plate also passes
        if (hasDigit) {
            if (plateShape) return new Search(cleaned, null);
        } else if (raw.strip().length() >= MIN_TEXT_LENGTH) {
            boolean oneWord = !SEPARATOR.matcher(raw.strip()).find();
            return new Search(plateShape && oneWord ? cleaned : null, raw.strip());
        }
        throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                "Plaka aramak için tam plakayı girin (örnek: 34 ABC 123 ya da yabancı plaka M AB 1234). "
                        + "Rakam içeren arama yalnızca tam plakayla eşleşir; serbest metin en az 4 harf olmalıdır.");
    }

    private static String viewedSummary(Query q, Search search) {
        List<String> used = new ArrayList<>();
        add(used, "from", q.from());
        add(used, "to", q.to());
        add(used, "action", q.action());
        add(used, "actor", q.actor());
        add(used, "actorRole", q.actorRole());
        add(used, "entityType", q.entityType());
        if (search != null) {
            // a value that may be a plate (also a letter-only one such as "TAXI") is stored masked
            add(used, "q", search.plate() != null ? PlateMasker.maskPlate(search.plate())
                    : PlateMasker.maskText(search.text()));
        }
        return "Geliştirici denetim kaydını görüntüledi" + (used.isEmpty() ? "" : " (" + String.join(", ", used) + ")");
    }

    private static void add(List<String> used, String name, String value) {
        if (value != null && !value.isBlank()) used.add(name + "=" + value.trim());
    }
}
