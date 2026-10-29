package com.bilpark.backend.model;

/**
 * User roles for Role-Based Access Control (RBAC).
 * ADMIN  - Full access including BI dashboard and income reports.
 * OFFICER - Field staff; can manage vehicles on their assigned street.
 * DEVELOPER - Maintenance (stage 5): license extension, system health, recent errors and admin
 *             password reset only. Written in the contract; every action is in the audit log.
 */
public enum Role {
    ADMIN,
    OFFICER,
    DEVELOPER
}
