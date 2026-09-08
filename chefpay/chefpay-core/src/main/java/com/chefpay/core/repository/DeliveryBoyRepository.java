package com.chefpay.core.repository;

import com.chefpay.core.domain.DeliveryBoy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryBoyRepository extends JpaRepository<DeliveryBoy, UUID> {

    List<DeliveryBoy> findByActiveTrue();
}
