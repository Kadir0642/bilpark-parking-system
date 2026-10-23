package com.bilpark.backend.auth;

import com.bilpark.backend.model.Zone;

/**
 * Response body for POST /api/auth/login. shiftId is the officer's open shift on this device (null for admin).
 */
public record LoginResponse(String token, String username, String role, Zone assignedZone, Long shiftId) {}
