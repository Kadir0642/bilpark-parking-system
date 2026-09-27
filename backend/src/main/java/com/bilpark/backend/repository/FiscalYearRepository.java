package com.bilpark.backend.repository;

import com.bilpark.backend.model.FiscalYear;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FiscalYearRepository extends JpaRepository<FiscalYear, Long> {
    Optional<FiscalYear> findByIsActiveTrue();
    List<FiscalYear> findAllByOrderByStartDateDesc();
}
