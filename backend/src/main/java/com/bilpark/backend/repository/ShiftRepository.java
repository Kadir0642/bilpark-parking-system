package com.bilpark.backend.repository;

import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, Long>, JpaSpecificationExecutor<Shift> {

    // Bu kullanıcının bu cihazdaki açık vardiyası
    Optional<Shift> findFirstByUserAndDeviceIdAndEndTimeIsNullOrderByStartTimeDesc(User user, String deviceId);

    // Tüm açık vardiyalar (panel için). Kullanıcı sorguda gelir: open-in-view yok, yanıt işlem dışında yazılır.
    @EntityGraph(attributePaths = "user", type = EntityGraph.EntityGraphType.LOAD)
    List<Shift> findByEndTimeIsNullOrderByStartTimeAsc();

    // Yönetici vardiya listesi (ShiftService.search): ad soyad için kullanıcı sorguda gelir
    @Override
    @EntityGraph(attributePaths = "user", type = EntityGraph.EntityGraphType.LOAD)
    Page<Shift> findAll(Specification<Shift> spec, Pageable pageable);

    // Kullanıcının tüm açık vardiyaları
    List<Shift> findByUserAndEndTimeIsNull(User user);

    long countByEndTimeIsNull();
}
