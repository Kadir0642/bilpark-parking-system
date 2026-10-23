package com.bilpark.backend.model;

/** Tariff lifecycle: DRAFT -> SCHEDULED -> ACTIVE -> ARCHIVED (cancel: DRAFT/SCHEDULED -> ARCHIVED). */
public enum TariffStatus {
    DRAFT,
    SCHEDULED,
    ACTIVE,
    ARCHIVED
}
