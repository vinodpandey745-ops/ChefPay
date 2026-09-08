package com.chefpay.core.repository;

import com.chefpay.core.domain.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    /** Unfiltered - kept for the several existing consumers (Recipe/PurchaseOrder/Replenishment/
     * SupplierInvoice/EOD/Dashboard) that predate Bistrodesk Phase 2's branch column and aren't
     * branch-aware yet; only {@code InventoryController}'s own listing endpoints apply branch
     * filtering (in-memory, in {@code InventoryService}, since a legacy stray branchless row must
     * still be tolerated defensively even though branch is mandatory for every new item - see
     * {@link InventoryItem#getBranch()}'s javadoc). */
    List<InventoryItem> findByActiveTrueOrderByNameAsc();

    Optional<InventoryItem> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    /** Bistrodesk Phase 2: branch-scoped duplicate-name check - see {@link InventoryItem#name}'s
     * javadoc for why uniqueness is now per-branch rather than global. Every new item always has a
     * branch as of the branch-isolation release, so this is now the only duplicate-name check
     * {@code InventoryService#createItem} performs. */
    boolean existsByNameIgnoreCaseAndBranchId(String name, UUID branchId);

    /** Pre-branch-isolation-release legacy path only - see {@link InventoryItem#getBranch()}'s
     * javadoc. {@code InventoryService#createItem} no longer calls this (branch is mandatory now);
     * retained only in case a not-yet-backfilled branchless row is ever queried directly. */
    boolean existsByNameIgnoreCaseAndBranchIsNull(String name);
}
