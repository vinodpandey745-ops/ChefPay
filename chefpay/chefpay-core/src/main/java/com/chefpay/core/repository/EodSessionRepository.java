package com.chefpay.core.repository;

import com.chefpay.core.domain.EodSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EodSessionRepository extends JpaRepository<EodSession, UUID> {

    Optional<EodSession> findByBusinessDate(LocalDate businessDate);

    List<EodSession> findAllByOrderByBusinessDateDesc();
}
