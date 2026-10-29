package com.bilpark.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Keep-alive endpoint. Point UptimeRobot / Render health checks at /api/health instead of
 * /api/parking/spots. It deliberately never touches the database (no repository, DataSource or JPA),
 * so pings keep the app awake without keeping the (serverless) database awake.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "time", Instant.now().toString());
    }
}
