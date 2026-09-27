package com.bilpark.backend.auth;

import com.bilpark.backend.model.Zone;

/**
 * Response body for POST /api/auth/login
 */
public record LoginResponse(String token, String username, String role, Zone assignedZone) {}
