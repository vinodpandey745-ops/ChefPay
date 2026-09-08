package com.chefpay.core.repository;

import com.chefpay.core.domain.InventoryTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, UUID> {

    List<InventoryTransaction> findByItemIdOrderByCreatedAtDesc(UUID itemId);
}
