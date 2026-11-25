package com.bilpark.backend.model;

/** Live sync event types (SSE "event:" name and sync_events.type). */
public enum SyncEventType {
    VEHICLE_ENTERED,
    VEHICLE_EXITED,
    VEHICLE_RUNAWAY,
    TARIFF_UPDATED,
    USER_DEACTIVATED,
    USER_UPDATED,      // zone/role/profile of the target user changed (stage 5)
    VEHICLE_CANCELLED, // an active vehicle entry was cancelled (wrong entry), zone scope
    RECORD_CANCELLED,  // a closed record was cancelled by the admin, zone scope
    LICENSE_UPDATED,   // license dates changed, global
    VEHICLE_TYPES_UPDATED, // the set of active vehicle types changed, global
    NOTIFICATION_CREATED   // a notification for the admin inbox, ADMIN viewers only
}
