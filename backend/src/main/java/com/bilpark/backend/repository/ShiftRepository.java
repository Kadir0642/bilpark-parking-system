package com.bilpark.backend.repository;

import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, Long>, JpaSpecificationExecutor<Shift> {

    // Bu kullanıcının bu cihazdaki açık vardiyası
    Optional<Shift> findFirstByUserAndDeviceIdAndEndTimeIsNullOrderByStartTimeDesc(User user, String deviceId);

    // Tüm açık vardiyalar (panel için)
    List<Shift> findByEndTimeIsNullOrderByStartTimeAsc();

    // Kullanıcının tüm açık vardiyaları
    List<Shift> findByUserAndEndTimeIsNull(User user);

    long countByEndTimeIsNull();
}
