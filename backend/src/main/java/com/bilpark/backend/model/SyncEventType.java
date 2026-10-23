package com.bilpark.backend.model;

/** Live sync event types (SSE "event:" name and sync_events.type). */
public enum SyncEventType {
    VEHICLE_ENTERED,
    VEHICLE_EXITED,
    VEHICLE_RUNAWAY,
    TARIFF_UPDATED,
    USER_DEACTIVATED
}
