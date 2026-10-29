package com.bilpark.backend.audit;

import com.bilpark.backend.common.DeviceIds;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.AuditLog;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.AuditLogRepository;
import com.bilpark.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Writes audit_logs rows: who (actor snapshot), when, which device (X-Device-Id) and IP,
 * what (action, entity) and a before/after summary as JSON.
 *
 * Runs in the caller's transaction: if the audited operation rolls back, so does its audit row.
 * Never put passwords or hashes into before/after.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final String SYSTEM = "system";

    private final AuditLogRepository repository;
    private final UserRepository userRepository;
    private final JsonMapper mapper;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, UserRepository userRepository, JsonMapper mapper, Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Actor = the authenticated user of the current request ("system" when there is none). */
    @Transactional
    public AuditLog log(AuditAction action, String entityType, Object entityId, String summary,
                        Object before, Object after) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return save(null, SYSTEM, null, action, entityType, entityId, summary, before, after);
        }
        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst().orElse(null);
        Long actorId = userRepository.findByUsername(auth.getName()).map(User::getId).orElse(null);
        return save(actorId, auth.getName(), role, action, entityType, entityId, summary, before, after);
    }

    /** Explicit actor (e.g. at login, before the security context holds the user). */
    @Transactional
    public AuditLog logAs(User actor, AuditAction action, String entityType, Object entityId, String summary,
                          Object before, Object after) {
        if (actor == null) return log(action, entityType, entityId, summary, before, after);
        return save(actor.getId(), actor.getUsername(), actor.getRole() != null ? actor.getRole().name() : null,
                action, entityType, entityId, summary, before, after);
    }

    /** Explicit actor by name only (e.g. a failed login with an unknown username). */
    @Transactional
    public AuditLog logAsName(String username, AuditAction action, String entityType, Object entityId, String summary,
                              Object before, Object after) {
        return save(null, username == null || username.isBlank() ? "?" : truncate(username, 255), null,
                action, entityType, entityId, summary, before, after);
    }

    /** Scheduled jobs and other actions without a user. */
    @Transactional
    public AuditLog logSystem(AuditAction action, String entityType, Object entityId, String summary,
                              Object before, Object after) {
        return save(null, SYSTEM, null, action, entityType, entityId, summary, before, after);
    }

    private AuditLog save(Long actorId, String username, String role, AuditAction action, String entityType,
                          Object entityId, String summary, Object before, Object after) {
        AuditLog row = new AuditLog();
        row.setCreatedAt(LocalDateTime.now(clock));
        row.setActorId(actorId);
        row.setActorUsername(username);
        row.setActorRole(role);
        HttpServletRequest req = currentRequest();
        if (req != null) {
            String device = req.getHeader("X-Device-Id");
            row.setDeviceId(device != null ? DeviceIds.sanitize(device) : null);
            row.setIpAddress(truncate(clientIp(req), 64));
        }
        row.setAction(action);
        row.setEntityType(truncate(entityType, 50));
        row.setEntityId(entityId != null ? truncate(String.valueOf(entityId), 100) : null);
        row.setSummary(truncate(summary, 500));
        row.setBeforeData(json(before));
        row.setAfterData(json(after));
        return repository.save(row);
    }

    /** JSON text of a summary object (Map, record, ...); null stays null. */
    public String json(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try {
            return mapper.writeValueAsString(o);
        } catch (RuntimeException e) {
            log.warn("Audit summary could not be serialized: {}", e.getClass().getSimpleName());
            return String.valueOf(o);
        }
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        return null;
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For"); // Render puts the client first
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
