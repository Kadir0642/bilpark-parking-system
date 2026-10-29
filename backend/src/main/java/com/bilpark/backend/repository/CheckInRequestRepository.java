package com.bilpark.backend.repository;

import com.bilpark.backend.model.CheckInRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CheckInRequestRepository extends JpaRepository<CheckInRequest, String> {
}
