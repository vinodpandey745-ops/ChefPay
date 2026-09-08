package com.chefpay.core.repository;

import com.chefpay.core.domain.Feature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FeatureRepository extends JpaRepository<Feature, UUID> {
    Optional<Feature> findByCodeIgnoreCase(String code);
}
