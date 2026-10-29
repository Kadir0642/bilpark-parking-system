package com.bilpark.backend.developer;

import com.bilpark.backend.audit.AuditQueryService;
import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.common.PlateMasker;
import com.bilpark.backend.common.PlateNormalizer;
import com.bilpark.backend.model.AuditAction;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // Province code followed by letters/digits: looks like the start of a plate
    private static final Pattern PLATE_PREFIX = Pattern.compile("^(0[1-9]|[1-7][0-9]|8[01])[A-Z]{1,3}[0-9]{0,5}$");

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
        String q = resolveSearch(query.q());
        AuditQueryService.Filter filter = new AuditQueryService.Filter(query.from(), query.to(), query.action(),
                query.actor(), query.actorRole(), query.entityType(), null, q);
        Map<String, Object> body = queries.list(filter, page, size);

        List<Map<String, Object>> masked = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) body.get("content")) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            m.put("entityId", PlateMasker.maskText((String) row.get("entityId")));
            m.put("summary", PlateMasker.maskText((String) row.get("summary")));
            m.put("before", PlateMasker.maskJson(row.get("before")));
            m.put("after", PlateMasker.maskJson(row.get("after")));
            masked.add(m);
        }
        body.put("content", masked);

        if (Math.max(0, page) == 0) {
            auditService.log(AuditAction.DEV_AUDIT_VIEWED, "System", "audit", viewedSummary(query), null, null);
        }
        return body;
    }

    /**
     * A full plate (any spelling) is matched exactly, in its stored form. A text that only looks like the start
     * of a plate is refused, so the search cannot be used to guess plates piece by piece.
     */
    static String resolveSearch(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String cleaned = PlateNormalizer.clean(raw);
        if (PlateNormalizer.isValid(cleaned)) return cleaned;
        if (PLATE_PREFIX.matcher(cleaned).matches()) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "Plaka aramak için tam plaka girin (örnek: 34 ABC 123).");
        }
        return raw;
    }

    private static String viewedSummary(Query q) {
        List<String> used = new ArrayList<>();
        add(used, "from", q.from());
        add(used, "to", q.to());
        add(used, "action", q.action());
        add(used, "actor", q.actor());
        add(used, "actorRole", q.actorRole());
        add(used, "entityType", q.entityType());
        add(used, "q", q.q() == null ? null : PlateMasker.maskText(PlateNormalizer.isValid(PlateNormalizer.clean(q.q()))
                ? PlateNormalizer.clean(q.q()) : q.q()));
        return "Geliştirici denetim kaydını görüntüledi" + (used.isEmpty() ? "" : " (" + String.join(", ", used) + ")");
    }

    private static void add(List<String> used, String name, String value) {
        if (value != null && !value.isBlank()) used.add(name + "=" + value.trim());
    }
}
