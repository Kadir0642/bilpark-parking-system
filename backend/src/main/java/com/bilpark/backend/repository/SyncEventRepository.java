package com.bilpark.backend.repository;

import com.bilpark.backend.model.SyncEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SyncEventRepository extends JpaRepository<SyncEvent, Long> {

    // Events after a version, oldest first (pass a page size limit)
    @Query("SELECT e FROM SyncEvent e WHERE e.id > :since ORDER BY e.id ASC")
    List<SyncEvent> findAfter(@Param("since") long since, Pageable page);

    @Query("SELECT COALESCE(MAX(e.id), 0) FROM SyncEvent e")
    long latestVersion();

    @Query("SELECT MIN(e.id) FROM SyncEvent e")
    Long oldestVersion();

    @Modifying
    @Query("DELETE FROM SyncEvent e WHERE e.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
