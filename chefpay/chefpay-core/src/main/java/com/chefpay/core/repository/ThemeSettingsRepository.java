package com.chefpay.core.repository;

import com.chefpay.core.domain.ThemeSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ThemeSettingsRepository extends JpaRepository<ThemeSettings, UUID> {
}
