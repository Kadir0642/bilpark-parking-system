package com.bilpark.backend.repository;

import com.bilpark.backend.model.Street;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StreetRepository extends JpaRepository<Street, Long> {

    List<Street> findAllByOrderBySortOrderAsc();

    Optional<Street> findByCode(String code);
}
