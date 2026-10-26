package com.bilpark.backend.service;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.common.PlateNormalizer;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.Subscription;
import com.bilpark.backend.model.SubscriptionStatus;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;

    private final TariffService tariffService;

    private final AuditService auditService;

    public SubscriptionService(SubscriptionRepository subscriptionRepository, TariffService tariffService,
                               AuditService auditService) {
        this.auditService = auditService;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffService = tariffService;
    }

    /**
     * Creates a new monthly subscription (a renewal after EXPIRED/CANCELLED is a new row).
     * At most one ACTIVE subscription per plate.
     */
    @Transactional
    public Subscription createSubscription(String licensePlate, String vehicleTypeStr,
                                           String ownerName, String ownerPhone) {
        String plate = PlateNormalizer.normalize(licensePlate);

        List<Subscription> actives = subscriptionRepository
                .findByPlateAndStatusForUpdate(plate, SubscriptionStatus.ACTIVE);
        for (Subscription s : actives) {
            if (s.isCurrentlyActive()) {
                throw ApiException.conflict(ErrorCodes.SUBSCRIPTION_EXISTS,
                        "Bu plakaya ait aktif bir abonelik zaten mevcut. Bitiş: " + s.getEndDate());
            }
        }
        // ACTIVE ama tarihi geçmiş satırlar önce EXPIRED yapılır
        if (!actives.isEmpty()) {
            actives.forEach(s -> s.setStatus(SubscriptionStatus.EXPIRED));
            subscriptionRepository.saveAllAndFlush(actives);
        }

        VehicleType type;
        try {
            type = VehicleType.valueOf(vehicleTypeStr.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            type = VehicleType.SMALL;
        }

        Subscription sub = new Subscription(plate, type, LocalDate.now(), ownerName, ownerPhone);
        // Aylık ücret geçerli tarifeden gelir
        Tariff tariff = tariffService.current();
        sub.setMonthlyFee(type == VehicleType.LARGE
                ? tariff.getSubscriptionLargeMonthly() : tariff.getSubscriptionSmallMonthly());
        try {
            Subscription saved = subscriptionRepository.saveAndFlush(sub);
            Map<String, Object> after = new LinkedHashMap<>(); // no owner name / phone
            after.put("plate", saved.getLicensePlate());
            after.put("vehicleType", saved.getVehicleType().name());
            after.put("startDate", String.valueOf(saved.getStartDate()));
            after.put("endDate", String.valueOf(saved.getEndDate()));
            after.put("monthlyFee", saved.getMonthlyFee());
            auditService.log(AuditAction.SUBSCRIPTION_CREATED, "Subscription", saved.getId(),
                    saved.getLicensePlate() + " için abonelik oluşturuldu", null, after);
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict(ErrorCodes.SUBSCRIPTION_EXISTS,
                    "Bu plakaya ait aktif bir abonelik zaten mevcut.");
        }
    }

    /**
     * Checks if a plate has a currently active subscription.
     * Returns a rich response map used by mobile check-in popup.
     */
    public Map<String, Object> checkSubscription(String licensePlate) {
        String plate = PlateNormalizer.clean(licensePlate);
        Optional<Subscription> opt = subscriptionRepository
                .findFirstByLicensePlateAndStatusOrderByEndDateDesc(plate, SubscriptionStatus.ACTIVE);

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
        String plate = PlateNormalizer.clean(licensePlate);
        List<Subscription> actives = subscriptionRepository
                .findByPlateAndStatusForUpdate(plate, SubscriptionStatus.ACTIVE);
        if (actives.isEmpty()) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND,
                    "Bu plakaya ait aktif abonelik bulunamadı: " + plate);
        }
        actives.forEach(s -> s.setStatus(SubscriptionStatus.CANCELLED));
        subscriptionRepository.saveAll(actives);
        Subscription first = actives.get(0);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("plate", first.getLicensePlate());
        before.put("status", "ACTIVE");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("plate", first.getLicensePlate());
        after.put("status", "CANCELLED");
        auditService.log(AuditAction.SUBSCRIPTION_CANCELLED, "Subscription", first.getId(),
                first.getLicensePlate() + " aboneliği iptal edildi", before, after);
        return first;
    }

    /**
     * Scheduled task: runs every day at 01:00 AM to mark expired subscriptions.
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void expireOldSubscriptions() {
        List<Subscription> expired = subscriptionRepository
                .findExpiredSubscriptions(LocalDate.now());
        expired.forEach(s -> s.setStatus(SubscriptionStatus.EXPIRED));
        subscriptionRepository.saveAll(expired);
        if (!expired.isEmpty()) {
            log.info("Expired {} subscriptions.", expired.size());
        }
    }
}
