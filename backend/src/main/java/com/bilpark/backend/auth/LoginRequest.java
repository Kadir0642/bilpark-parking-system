package com.bilpark.backend.auth;

/**
 * Request body for POST /api/auth/login
 */
public record LoginRequest(String username, String password) {}
