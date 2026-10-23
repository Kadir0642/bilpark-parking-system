package com.bilpark.backend.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TariffScheduleRequest {
    private LocalDateTime effectiveFrom; // "2026-11-01T00:00:00"
    private String password;             // admin password, re-checked
}
