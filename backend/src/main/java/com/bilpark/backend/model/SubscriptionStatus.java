package com.bilpark.backend.model;

/**
 * Status of a monthly parking subscription.
 */
public enum SubscriptionStatus {
    ACTIVE,    // Currently valid subscription
    EXPIRED,   // Past the end date
    CANCELLED  // Manually cancelled by admin
}
