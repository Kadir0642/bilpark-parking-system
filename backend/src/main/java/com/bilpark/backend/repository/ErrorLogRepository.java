package com.bilpark.backend.repository;

import com.bilpark.backend.model.ErrorLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ErrorLogRepository extends JpaRepository<ErrorLog, Long> {
    List<ErrorLog> findAllByOrderByIdDesc(Pageable page);

    long countByCreatedAtGreaterThanEqual(LocalDateTime since);
}
