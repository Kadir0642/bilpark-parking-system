package com.bilpark.backend.repository;

import com.bilpark.backend.model.AdminNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AdminNotificationRepository extends JpaRepository<AdminNotification, Long> {

    @Query("SELECT n FROM AdminNotification n ORDER BY n.createdAt DESC, n.id DESC")
    List<AdminNotification> recent(Pageable pageable);

    @Query("SELECT n FROM AdminNotification n WHERE n.type IN :types ORDER BY n.createdAt DESC, n.id DESC")
    List<AdminNotification> recentOfTypes(@Param("types") List<String> types, Pageable pageable);

    @Query("SELECT COUNT(n) FROM AdminNotification n WHERE NOT EXISTS "
            + "(SELECT 1 FROM AdminNotificationRead r WHERE r.notificationId = n.id AND r.userId = :uid)")
    long countUnread(@Param("uid") Long userId);

    @Query("SELECT n.id FROM AdminNotification n WHERE NOT EXISTS "
            + "(SELECT 1 FROM AdminNotificationRead r WHERE r.notificationId = n.id AND r.userId = :uid)")
    List<Long> unreadIds(@Param("uid") Long userId);

    @Modifying
    @Query("DELETE FROM AdminNotificationRead r WHERE r.notificationId IN "
            + "(SELECT n.id FROM AdminNotification n WHERE n.createdAt < :cutoff)")
    int deleteReadsOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM AdminNotification n WHERE n.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
