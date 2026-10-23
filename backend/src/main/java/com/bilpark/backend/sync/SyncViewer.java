package com.bilpark.backend.sync;

import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.User;

/** Who is looking at the event stream: decides which events are visible. */
public record SyncViewer(Long userId, Role role, Long zoneId, String street) {

    public static SyncViewer of(User user) {
        var zone = user.getAssignedZone();
        return new SyncViewer(user.getId(), user.getRole(),
                zone != null ? zone.getId() : null,
                zone != null && zone.getStreet() != null ? zone.getStreet().name() : null);
    }
}
