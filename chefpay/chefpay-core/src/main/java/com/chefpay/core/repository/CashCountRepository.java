package com.chefpay.core.repository;

import com.chefpay.core.domain.CashCount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CashCountRepository extends JpaRepository<CashCount, UUID> {

    Optional<CashCount> findByEodSessionId(UUID eodSessionId);
}
