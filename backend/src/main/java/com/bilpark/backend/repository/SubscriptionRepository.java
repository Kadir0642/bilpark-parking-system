package com.bilpark.backend.repository;

import com.bilpark.backend.model.Subscription;
import com.bilpark.backend.model.SubscriptionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// Plakalar PlateNormalizer ile normalize edilmiş saklanır ve aranır.
// Bir plakanın birden çok satırı olabilir (yenileme); en fazla biri ACTIVE'dir.
@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    // The plate's ACTIVE subscription, if any
    Optional<Subscription> findFirstByLicensePlateAndStatusOrderByEndDateDesc(
            String licensePlate, SubscriptionStatus status);

    // Same rows, locked for update (create/cancel run in a transaction)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.licensePlate = :plate AND s.status = :status")
    List<Subscription> findByPlateAndStatusForUpdate(@Param("plate") String plate,
                                                     @Param("status") SubscriptionStatus status);

    // All rows of a plate, newest first (history/admin view)
    List<Subscription> findByLicensePlateOrderByStartDateDesc(String licensePlate);

    // All active subscriptions
    List<Subscription> findByStatus(SubscriptionStatus status);

    // Plates (from the given list) with a currently valid ACTIVE subscription: one query for a whole spot list
    @Query("SELECT DISTINCT s.licensePlate FROM Subscription s WHERE s.licensePlate IN :plates "
            + "AND s.status = com.bilpark.backend.model.SubscriptionStatus.ACTIVE "
            + "AND s.startDate <= :today AND s.endDate >= :today")
    List<String> findCurrentlyActivePlates(@Param("plates") java.util.Collection<String> plates,
                                           @Param("today") LocalDate today);

    // Find expired subscriptions that haven't been marked expired yet
    @Query("SELECT s FROM Subscription s WHERE s.status = com.bilpark.backend.model.SubscriptionStatus.ACTIVE AND s.endDate < :today")
    List<Subscription> findExpiredSubscriptions(@Param("today") LocalDate today);
}
