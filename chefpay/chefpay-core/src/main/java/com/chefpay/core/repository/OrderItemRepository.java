package com.chefpay.core.repository;

import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    /** Backs the kitchen queue (Phase 3): every line still "in flight" in the kitchen, oldest first. */
    List<OrderItem> findByStatusInOrderBySentAtAsc(Collection<OrderItemStatus> statuses);

    /** Backs the KOT ticket listing (Round 8): the most recent 200 sent lines, newest KOT first. */
    List<OrderItem> findTop200ByKotNumberNotNullOrderByKotNumberDesc();
}
