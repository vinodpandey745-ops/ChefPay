package com.chefpay.core.repository;

import com.chefpay.core.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    List<NotificationLog> findByOrderByAttemptedAtDesc();

    List<NotificationLog> findByAnomalyIdOrderByAttemptedAtDesc(UUID anomalyId);

    /** Final round - data-retention auto-purge: delivery logs are high-volume and low-stakes
     * (unlike the anomalies they're about), so these are always eligible for purge purely by age,
     * no status/outcome check needed. Returns the number of rows deleted. */
    long deleteByAttemptedAtBefore(LocalDateTime cutoff);
}
