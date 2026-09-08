package com.chefpay.core.repository;

import com.chefpay.core.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByBranchIdOrderByCreatedAtDesc(UUID branchId);

    List<Notification> findByBranchIdAndReadFalseOrderByCreatedAtDesc(UUID branchId);

    long countByBranchIdAndReadFalse(UUID branchId);
}
