package com.bilpark.backend.repository;

import com.bilpark.backend.model.AdminNotificationRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AdminNotificationReadRepository
        extends JpaRepository<AdminNotificationRead, AdminNotificationRead.Key> {

    @Query("SELECT r.notificationId FROM AdminNotificationRead r WHERE r.userId = :uid AND r.notificationId IN :ids")
    List<Long> readIds(@Param("uid") Long userId, @Param("ids") Collection<Long> ids);

    @Query("SELECT COUNT(r) FROM AdminNotificationRead r WHERE r.notificationId = :id")
    long countFor(@Param("id") Long notificationId);
}
