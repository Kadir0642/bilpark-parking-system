package com.bilpark.backend.sync;

import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.SyncEvent;
import com.bilpark.backend.model.SyncScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncVisibilityTest {

    private static final SyncViewer ADMIN = new SyncViewer(1L, Role.ADMIN, null, null);
    private static final SyncViewer OFFICER = new SyncViewer(7L, Role.OFFICER, 1L, "TEVFIK_BEY");
    private static final SyncViewer NO_ZONE = new SyncViewer(8L, Role.OFFICER, null, null);

    private static SyncEvent ev(SyncScope scope, Long zoneId, String street, Long target) {
        SyncEvent e = new SyncEvent();
        e.setScope(scope);
        e.setZoneId(zoneId);
        e.setStreet(street);
        e.setTargetUserId(target);
        return e;
    }

    @Test
    void adminSeesEverything() {
        assertTrue(SyncService.isVisible(ev(SyncScope.ZONE, 5L, "CUMHURIYET", null), ADMIN));
        assertTrue(SyncService.isVisible(ev(SyncScope.USER, null, null, 99L), ADMIN));
        assertTrue(SyncService.isVisible(ev(SyncScope.GLOBAL, null, null, null), ADMIN));
    }

    @Test
    void officerSeesOwnZoneOnly() {
        assertTrue(SyncService.isVisible(ev(SyncScope.ZONE, 1L, "TEVFIK_BEY", null), OFFICER));
        assertFalse(SyncService.isVisible(ev(SyncScope.ZONE, 2L, "TEVFIK_BEY", null), OFFICER));
    }

    @Test
    void streetOnlyLegacyEventMatchesByStreet() {
        assertTrue(SyncService.isVisible(ev(SyncScope.ZONE, null, "TEVFIK_BEY", null), OFFICER));
        assertFalse(SyncService.isVisible(ev(SyncScope.ZONE, null, "CUMHURIYET", null), OFFICER));
    }

    @Test
    void globalAndUserScopes() {
        assertTrue(SyncService.isVisible(ev(SyncScope.GLOBAL, null, null, null), OFFICER));
        assertTrue(SyncService.isVisible(ev(SyncScope.USER, null, null, 7L), OFFICER));
        assertFalse(SyncService.isVisible(ev(SyncScope.USER, null, null, 9L), OFFICER));
    }

    @Test
    void officerWithoutZoneSeesOnlyGlobalAndOwnUser() {
        assertTrue(SyncService.isVisible(ev(SyncScope.GLOBAL, null, null, null), NO_ZONE));
        assertTrue(SyncService.isVisible(ev(SyncScope.USER, null, null, 8L), NO_ZONE));
        assertFalse(SyncService.isVisible(ev(SyncScope.ZONE, 1L, "TEVFIK_BEY", null), NO_ZONE));
        assertFalse(SyncService.isVisible(ev(SyncScope.ZONE, null, "TEVFIK_BEY", null), NO_ZONE));
    }
}
