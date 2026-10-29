package com.bilpark.backend.repository;

import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.TariffStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TariffRepository extends JpaRepository<Tariff, Long> {

    Optional<Tariff> findFirstByStatus(TariffStatus status);

    List<Tariff> findAllByOrderByIdDesc();
}
