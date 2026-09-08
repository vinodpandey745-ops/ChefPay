package com.chefpay.core.repository;

import com.chefpay.core.domain.SupportSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SupportSettingsRepository extends JpaRepository<SupportSettings, UUID> {
}
