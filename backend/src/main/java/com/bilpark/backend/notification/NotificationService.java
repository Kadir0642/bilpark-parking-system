package com.bilpark.backend.notification;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.AdminNotification;
import com.bilpark.backend.model.AdminNotificationRead;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.repository.AdminNotificationReadRepository;
import com.bilpark.backend.repository.AdminNotificationRepository;
import com.bilpark.backend.sync.ChangePublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The admin's notification inbox (docs/api-bildirimler.md section 2). Creating a notification runs in the caller's
 * transaction and tells the connected admins after commit (NOTIFICATION_CREATED). Reading is never audited.
 */
@Service
public class NotificationService {

    public static final String SYSTEM_UPDATED = "SYSTEM_UPDATED";
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String ADMIN_PASSWORD_RESET = "ADMIN_PASSWORD_RESET";
    public static final String LICENSE_UPDATED = "LICENSE_UPDATED";

    public static final int MAX_LIMIT = 200;

    private final AdminNotificationRepository notifications;
    private final AdminNotificationReadRepository reads;
    private final ChangePublisher publisher;
    private final Clock clock;

    public NotificationService(AdminNotificationRepository notifications, AdminNotificationReadRepository reads,
                               ChangePublisher publisher, Clock clock) {
        this.notifications = notifications;
        this.reads = reads;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Transactional
    public AdminNotification create(String type, String title, String body, LocalDateTime validUntil, String createdBy) {
        AdminNotification n = new AdminNotification();
        n.setType(type);
        n.setTitle(title);
        n.setBody(body == null || body.isBlank() ? null : body);
        n.setCreatedAt(LocalDateTime.now(clock));
        n.setCreatedBy(createdBy);
        n.setValidUntil(validUntil);
        AdminNotification saved = notifications.save(n);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", saved.getId());
        payload.put("type", saved.getType());
        payload.put("title", saved.getTitle());
        publisher.adminEvent(SyncEventType.NOTIFICATION_CREATED, payload);
        return saved;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Long userId, int limit) {
        List<AdminNotification> rows = notifications.recent(PageRequest.of(0, clampLimit(limit)));
        Set<Long> readSet = readSet(userId, rows);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminNotification n : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("type", n.getType());
            m.put("title", n.getTitle());
            m.put("body", n.getBody());
            m.put("createdAt", n.getCreatedAt());
            m.put("validUntil", n.getValidUntil());
            m.put("read", readSet.contains(n.getId()));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unread", notifications.countUnread(userId));
        out.put("items", items);
        return out;
    }

    /** Announcements the developer sent (ANNOUNCEMENT and MAINTENANCE), newest first, with how many users read each. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AdminNotification n : notifications.recentOfTypes(List.of(ANNOUNCEMENT, MAINTENANCE),
                PageRequest.of(0, clampLimit(limit)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("type", n.getType());
            m.put("title", n.getTitle());
            m.put("body", n.getBody());
            m.put("createdAt", n.getCreatedAt());
            m.put("createdBy", n.getCreatedBy());
            m.put("validUntil", n.getValidUntil());
            m.put("readCount", reads.countFor(n.getId()));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public void markRead(Long notificationId, Long userId) {
        if (!notifications.existsById(notificationId)) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND, "Bildirim bulunamadı.");
        }
        insertRead(notificationId, userId);
    }

    @Transactional
    public void markAllRead(Long userId) {
        for (Long id : notifications.unreadIds(userId)) insertRead(id, userId);
    }

    private void insertRead(Long notificationId, Long userId) {
        AdminNotificationRead.Key key = new AdminNotificationRead.Key();
        key.setNotificationId(notificationId);
        key.setUserId(userId);
        if (reads.existsById(key)) return;
        AdminNotificationRead r = new AdminNotificationRead();
        r.setNotificationId(notificationId);
        r.setUserId(userId);
        r.setReadAt(LocalDateTime.now(clock));
        reads.save(r);
    }

    private Set<Long> readSet(Long userId, List<AdminNotification> rows) {
        if (rows.isEmpty()) return Set.of();
        return new HashSet<>(reads.readIds(userId, rows.stream().map(AdminNotification::getId).toList()));
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
