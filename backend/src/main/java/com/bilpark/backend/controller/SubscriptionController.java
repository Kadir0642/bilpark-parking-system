package com.bilpark.backend.controller;

import com.bilpark.backend.model.Subscription;
import com.bilpark.backend.service.SubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /**
     * Creates a new monthly subscription.
     * Accessible by anyone (citizens from the web portal).
     */
    @PostMapping("/create")
    public ResponseEntity<?> createSubscription(@RequestBody @jakarta.validation.Valid com.bilpark.backend.dto.SubscriptionRequest request) {
        try {
            Subscription sub = subscriptionService.createSubscription(
                request.getLicensePlate(), 
                request.getVehicleType(), 
                request.getOwnerName(), 
                request.getOwnerPhone()
            );
            return ResponseEntity.ok(sub);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Checks if a plate has an active subscription.
     * Used by the mobile app during check-in.
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkSubscription(@RequestParam String plate) {
        Map<String, Object> response = subscriptionService.checkSubscription(plate);
        return ResponseEntity.ok(response);
    }

    /**
     * Lists all active subscriptions.
     * Admin only.
     */
    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Subscription>> getAllActiveSubscriptions() {
        return ResponseEntity.ok(subscriptionService.getAllActiveSubscriptions());
    }

    /**
     * Cancels an active subscription.
     * Admin only.
     */
    @PostMapping("/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> cancelSubscription(@RequestParam String plate) {
        try {
            Subscription cancelled = subscriptionService.cancelSubscription(plate);
            return ResponseEntity.ok(cancelled);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
