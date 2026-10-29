package com.bilpark.backend.model;

/** What an audit_logs row records (stage 5). Stored as its name (varchar 50). */
public enum AuditAction {
    // Session
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    PASSWORD_CHANGED,

    // Vehicles and records
    VEHICLE_CHECK_IN,
    VEHICLE_CHECK_OUT,
    VEHICLE_RUNAWAY,
    SPOT_CANCELLED,      // active entry cancelled (wrong plate / wrong entry)
    RECORD_CANCELLED,    // closed record (PAID / RUNAWAY) cancelled by the admin

    // Tariffs
    TARIFF_DRAFT_CREATED,
    TARIFF_DRAFT_UPDATED,
    TARIFF_SCHEDULED,
    TARIFF_CANCELLED,
    TARIFF_ACTIVATED,    // system: a scheduled tariff took effect

    // Subscriptions
    SUBSCRIPTION_CREATED,
    SUBSCRIPTION_CANCELLED,

    // Users
    USER_CREATED,
    USER_UPDATED,
    USER_ZONE_ASSIGNED,
    USER_ACTIVATED,
    USER_DEACTIVATED,
    USER_PASSWORD_RESET,

    // Fiscal years
    FISCAL_YEAR_CREATED,
    FISCAL_YEAR_ARCHIVED,
    FISCAL_YEAR_DELETED,

    // Settings, license, reports, retention
    SETTINGS_UPDATED,
    LICENSE_UPDATED,
    REPORT_EXPORTED,
    RETENTION_RUN,

    // Developer (super admin) actions, all visible to the admin
    DEVELOPER_ACCOUNT_CREATED,
    DEV_HEALTH_VIEWED,
    DEV_ERRORS_VIEWED,
    DEV_ADMIN_PASSWORD_RESET
}
