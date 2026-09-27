package com.bilpark.backend.service;

import com.bilpark.backend.model.Subscription;
import com.bilpark.backend.model.SubscriptionStatus;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.SubscriptionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionService(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Creates a new monthly subscription.
     * Fee is calculated automatically based on vehicle type.
     */
    @Transactional
    public Subscription createSubscription(String licensePlate, String vehicleTypeStr,
                                           String ownerName, String ownerPhone) {
        String plate = licensePlate.toUpperCase().trim();

        // Check if an active subscription already exists for this plate
        Optional<Subscription> existing = subscriptionRepository
                .findByLicensePlateIgnoreCaseAndStatus(plate, SubscriptionStatus.ACTIVE);

        if (existing.isPresent() && existing.get().isCurrentlyActive()) {
            throw new RuntimeException(
                    "Bu plakaya ait aktif bir abonelik zaten mevcut. Bitiş: "
                    + existing.get().getEndDate());
        }

        VehicleType type;
        try {
            type = VehicleType.valueOf(vehicleTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = VehicleType.SMALL;
        }

        Subscription sub = new Subscription(plate, type, LocalDate.now(), ownerName, ownerPhone);
        return subscriptionRepository.save(sub);
    }

    /**
     * Checks if a plate has a currently active subscription.
     * Returns a rich response map used by mobile check-in popup.
     */
    public Map<String, Object> checkSubscription(String licensePlate) {
        Optional<Subscription> opt = subscriptionRepository
                .findByLicensePlateIgnoreCaseAndStatus(
                        licensePlate.toUpperCase(), SubscriptionStatus.ACTIVE);

        if (opt.isEmpty() || !opt.get().isCurrentlyActive()) {
            return Map.of("hasSubscription", false);
        }

        Subscription sub = opt.get();
        return Map.of(
                "hasSubscription", true,
                "licensePlate",    sub.getLicensePlate(),
                "vehicleType",     sub.getVehicleType().name(),
                "startDate",       sub.getStartDate().toString(),
                "endDate",         sub.getEndDate().toString(),
                "ownerName",       sub.getOwnerName() != null ? sub.getOwnerName() : "",
                "monthlyFee",      sub.getMonthlyFee()
        );
    }

    /** Lists all active subscriptions (for admin panel). */
    public List<Subscription> getAllActiveSubscriptions() {
        return subscriptionRepository.findByStatus(SubscriptionStatus.ACTIVE);
    }

    /** Lists all subscriptions regardless of status (for admin history). */
    public List<Subscription> getAllSubscriptions() {
        return subscriptionRepository.findAll();
    }

    /** Cancels a subscription by plate (admin action). */
    @Transactional
    public Subscription cancelSubscription(String licensePlate) {
        Subscription sub = subscriptionRepository
                .findByLicensePlateIgnoreCaseAndStatus(
                        licensePlate.toUpperCase(), SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new RuntimeException(
                        "Bu plakaya ait aktif abonelik bulunamadı: " + licensePlate));
        sub.setStatus(SubscriptionStatus.CANCELLED);
        return subscriptionRepository.save(sub);
    }

    /**
     * Scheduled task: runs every day at 01:00 AM to mark expired subscriptions.
     * This keeps the status column accurate without manual intervention.
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void expireOldSubscriptions() {
        List<Subscription> expired = subscriptionRepository
                .findExpiredSubscriptions(LocalDate.now());
        expired.forEach(s -> s.setStatus(SubscriptionStatus.EXPIRED));
        subscriptionRepository.saveAll(expired);
        if (!expired.isEmpty()) {
            System.out.println("[SubscriptionService] Expired " + expired.size() + " subscriptions.");
        }
    }
}
