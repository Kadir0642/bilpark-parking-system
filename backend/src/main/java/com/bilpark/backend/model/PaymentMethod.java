package com.bilpark.backend.model;

/**
 * How a parking fee was collected.
 * ONLINE is reserved for the payment-provider flow (stage 4) and is rejected until then.
 */
public enum PaymentMethod {
    CASH,         // Nakit
    POS,          // Personelin POS cihazı (slip no tutulmaz)
    SUBSCRIPTION, // Aylık abonelik, ücret 0
    ONLINE        // Ayrılmış: Aşama 4
}
