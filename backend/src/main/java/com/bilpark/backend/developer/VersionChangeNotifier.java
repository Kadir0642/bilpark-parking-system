package com.bilpark.backend.developer;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.notification.NotificationService;
import com.bilpark.backend.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * At startup compares the running version with the one stored in app_settings. A different version creates a
 * SYSTEM_UPDATED notification; the first start only stores the version. Never blocks the application from starting.
 */
@Component
public class VersionChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(VersionChangeNotifier.class);

    private final SettingsService settings;
    private final NotificationService notifications;

    public VersionChangeNotifier(SettingsService settings, NotificationService notifications) {
        this.settings = settings;
        this.notifications = notifications;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            check(DeveloperService.appVersion());
        } catch (RuntimeException e) {
            log.warn("Sürüm karşılaştırması yapılamadı", e);
        }
    }

    /** @return true when a notification was created. */
    public boolean check(String current) {
        try {
            if (current == null || current.isBlank()) return false;
            Optional<String> last = settings.get(SettingsService.KEY_LAST_APP_VERSION);
            if (last.isPresent() && last.get().equals(current)) return false;
            settings.put(SettingsService.KEY_LAST_APP_VERSION, current);
            if (last.isEmpty()) return false; // first installation: only remember the version
            notifications.create(NotificationService.SYSTEM_UPDATED, "Sistem güncellendi",
                    "Sistem yeni bir sürüme güncellendi.", null, AuditService.SYSTEM);
            return true;
        } catch (RuntimeException e) {
            log.warn("Sürüm karşılaştırması yapılamadı", e);
            return false;
        }
    }
}
