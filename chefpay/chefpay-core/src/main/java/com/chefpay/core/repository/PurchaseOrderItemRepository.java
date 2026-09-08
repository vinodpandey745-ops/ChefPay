package com.chefpay.core.repository;

import com.chefpay.core.domain.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, UUID> {

    List<PurchaseOrderItem> findByPurchaseOrderIdOrderByCreatedAtAsc(UUID purchaseOrderId);
}
