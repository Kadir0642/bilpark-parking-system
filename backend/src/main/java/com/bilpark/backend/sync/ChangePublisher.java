package com.bilpark.backend.sync;

import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.SyncEventType;

import java.util.Map;

/**
 * Publishes a data change to live clients.
 * The change is written to sync_events inside the caller's transaction (its id is the sync version),
 * and pushed over SSE only after that transaction commits (immediately when there is none).
 */
public interface ChangePublisher {

    /** Vehicle events: officers of that zone (or of that street when zoneId is null) and admins. */
    void zoneEvent(SyncEventType type, Long zoneId, StreetLocation street, Map<String, Object> payload);

    /** Everyone (e.g. TARIFF_UPDATED). */
    void globalEvent(SyncEventType type, Map<String, Object> payload);

    /** One user and admins (e.g. USER_DEACTIVATED). */
    void userEvent(SyncEventType type, Long targetUserId, Map<String, Object> payload);

    /** Admins only (other roles never see it, also not on replay). */
    default void adminEvent(SyncEventType type, Map<String, Object> payload) {
    }
}
