package com.bilpark.backend.repository;

import com.bilpark.backend.model.LicenseInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LicenseInfoRepository extends JpaRepository<LicenseInfo, Long> {
}
