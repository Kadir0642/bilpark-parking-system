package com.bilpark.backend.repository;

import com.bilpark.backend.model.Subscription;
import com.bilpark.backend.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    // Find active subscription by plate (case-insensitive)
    Optional<Subscription> findByLicensePlateIgnoreCaseAndStatus(
            String licensePlate, SubscriptionStatus status);

    // Find any subscription by plate (for history/admin view)
    Optional<Subscription> findByLicensePlateIgnoreCase(String licensePlate);

    // All active subscriptions
    List<Subscription> findByStatus(SubscriptionStatus status);

    // Find expired subscriptions that haven't been marked expired yet
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.endDate < :today")
    List<Subscription> findExpiredSubscriptions(LocalDate today);
}
