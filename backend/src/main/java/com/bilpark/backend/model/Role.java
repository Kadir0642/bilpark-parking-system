package com.bilpark.backend.model;

/**
 * User roles for Role-Based Access Control (RBAC).
 * ADMIN  - Full access including BI dashboard and income reports.
 * OFFICER - Field staff; can manage vehicles on their assigned street.
 */
public enum Role {
    ADMIN,
    OFFICER
}
