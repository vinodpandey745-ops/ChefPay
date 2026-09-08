package com.chefpay.core.repository;

import com.chefpay.core.domain.KitchenStation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KitchenStationRepository extends JpaRepository<KitchenStation, UUID> {
    List<KitchenStation> findByActiveTrueOrderByDisplayOrderAsc();
}
