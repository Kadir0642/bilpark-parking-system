package com.bilpark.backend.model;

/** Who receives a sync event: one zone (officers of that zone + admins), everyone, or one user (+ admins). */
public enum SyncScope {
    ZONE,
    GLOBAL,
    USER
}
