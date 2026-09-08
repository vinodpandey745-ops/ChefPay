package com.chefpay.core.repository;

import com.chefpay.core.domain.PurchaseOrder;
import com.chefpay.core.domain.PurchaseOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    List<PurchaseOrder> findByOrderByCreatedAtDesc();

    /** Branch-scoped listing (§23) - every screen that lists POs filters through this rather than
     * {@link #findByOrderByCreatedAtDesc} once a user's branch access is anything less than "all
     * branches," mirroring {@code OrderRepository#findByStatusNotInAndBranch}'s existing precedent. */
    List<PurchaseOrder> findByBranchIdOrderByCreatedAtDesc(UUID branchId);

    List<PurchaseOrder> findByStatusOrderByCreatedAtDesc(PurchaseOrderStatus status);

    Optional<PurchaseOrder> findByPoNumber(String poNumber);

    @Query("SELECT poi FROM PurchaseOrderItem poi WHERE poi.inventoryItem.id = :inventoryItemId "
            + "AND poi.purchaseOrder.status IN :openStatuses")
    List<com.chefpay.core.domain.PurchaseOrderItem> findOpenItemsForInventoryItem(
            @Param("inventoryItemId") UUID inventoryItemId, @Param("openStatuses") List<PurchaseOrderStatus> openStatuses);
}
