package com.chefpay.core.repository;

import com.chefpay.core.domain.Discount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DiscountRepository extends JpaRepository<Discount, UUID> {

    List<Discount> findByActiveTrueOrderByNameAsc();

    List<Discount> findAllByOrderByNameAsc();
}
