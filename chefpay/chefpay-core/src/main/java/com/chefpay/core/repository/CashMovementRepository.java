package com.chefpay.core.repository;

import com.chefpay.core.domain.CashMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface CashMovementRepository extends JpaRepository<CashMovement, UUID> {

    List<CashMovement> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
}
