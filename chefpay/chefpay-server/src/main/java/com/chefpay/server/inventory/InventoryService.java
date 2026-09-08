package com.chefpay.server.inventory;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.InventoryItem;
import com.chefpay.core.domain.InventoryTransaction;
import com.chefpay.core.domain.InventoryTransactionType;
import com.chefpay.core.domain.Supplier;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.InventoryItemRepository;
import com.chefpay.core.repository.InventoryTransactionRepository;
import com.chefpay.core.repository.SupplierRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.notifications.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stock tracking for {@link InventoryItem} (Phase 5, ARCHITECTURE.md §13). Every quantity change
 * goes through {@link #recordTransaction}, never a direct setter on {@code quantityOnHand} - that
 * keeps {@link InventoryTransaction} a complete, auditable ledger of "who changed what stock and
 * why," the same discipline {@code BillingService} applies to cash movements.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryItemRepository itemRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final AppUserRepository appUserRepository;
    private final SupplierRepository supplierRepository;
    private final BranchRepository branchRepository;
    private final BranchAccessService branchAccessService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    /** Bistrodesk branch-isolation release (requirement #3): every item created from now on always
     * has a branch (see {@code InventoryController#createItem}'s mandatory {@code
     * resolveEffectiveBranchId} resolution) - {@code accessibleBranchIds} is {@code null} only for a
     * genuinely ambiguous unrestricted caller (no filter, every item) and otherwise a restricted
     * caller sees only items at their own branch(es). The {@code item.getBranch() == null} arm below
     * is a startup-only safety net for a not-yet-backfilled legacy row (see {@code DataSeeder
     * #ensureInventoryItemBranchBackfill}) - real request traffic should never see a branchless item
     * once that backfill has run. In-memory filtering rather than a repository query: a plain
     * {@code branch_id IN (...)} can't also express "or branch_id IS NULL" without a bespoke query,
     * and this catalog is small enough that filtering after one indexed fetch is simpler and just as
     * correct. */
    @Transactional(readOnly = true)
    public List<InventoryItem> listItems(Set<UUID> accessibleBranchIds) {
        return itemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(i -> visible(i, accessibleBranchIds))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> listLowStock(Set<UUID> accessibleBranchIds) {
        return itemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(i -> visible(i, accessibleBranchIds))
                .filter(this::isLowStock)
                .toList();
    }

    private boolean visible(InventoryItem item, Set<UUID> accessibleBranchIds) {
        return accessibleBranchIds == null || item.getBranch() == null
                || accessibleBranchIds.contains(item.getBranch().getId());
    }

    public boolean isLowStock(InventoryItem item) {
        return item.getReorderThreshold() != null && item.getQuantityOnHand().compareTo(item.getReorderThreshold()) <= 0;
    }

    /** Bistrodesk branch-isolation release (requirement #3, reversing Phase 2's "unassigned/shared
     * item" design): {@code branchId} is now mandatory - {@code InventoryController#createItem}
     * always resolves one via {@code BranchAccessService#resolveEffectiveBranchId} before calling
     * this, but this method re-asserts it rather than trusting every caller to have done so (same
     * belt-and-suspenders discipline as {@code OrderService}'s own branch-required checks), since a
     * branchless item is exactly the "shared/leaks into every branch" resting state this release
     * exists to close. Caller authorization for a non-default {@code branchId} is still the caller's
     * own responsibility (see {@code InventoryController#createItem}) - this method only enforces
     * "a branch exists" plus the duplicate-name check. */
    @Transactional
    public InventoryItem createItem(String name, String unit, BigDecimal openingQuantity, BigDecimal reorderThreshold,
                                     BigDecimal costPerUnit, UUID branchId, UUID actorUserId) {
        if (branchId == null) {
            throw ApiException.badRequest("BRANCH_REQUIRED", "An inventory item must belong to a branch.");
        }
        boolean duplicate = itemRepository.existsByNameIgnoreCaseAndBranchId(name, branchId);
        if (duplicate) {
            throw ApiException.badRequest("DUPLICATE_ITEM", "An inventory item named '" + name + "' already exists at this branch.");
        }
        Branch branch = branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        InventoryItem saved = itemRepository.save(InventoryItem.builder()
                .name(name)
                .unit(unit)
                .quantityOnHand(openingQuantity == null ? BigDecimal.ZERO : openingQuantity)
                .reorderThreshold(reorderThreshold)
                .costPerUnit(costPerUnit)
                .branch(branch)
                .build());
        auditService.record(actorUserId, null, "InventoryItem", saved.getId(), "CREATE", null, saved.getName(), null, CorrelationIdHolder.get());
        return saved;
    }

    @Transactional
    public InventoryItem updateItem(UUID id, String name, String unit, BigDecimal reorderThreshold, BigDecimal costPerUnit,
                                     Boolean active, long version, UUID actorUserId) {
        InventoryItem item = itemRepository.findById(id).orElseThrow(() -> ApiException.notFound("Inventory item not found"));
        assertItemAccess(item, actorUserId);
        if (item.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(InventoryItem.class, id);
        }
        if (name != null) item.setName(name);
        if (unit != null) item.setUnit(unit);
        if (reorderThreshold != null) item.setReorderThreshold(reorderThreshold);
        if (costPerUnit != null) item.setCostPerUnit(costPerUnit);
        if (active != null) item.setActive(active);
        InventoryItem saved = itemRepository.save(item);
        auditService.record(actorUserId, null, "InventoryItem", saved.getId(), "UPDATE", null, null, null, CorrelationIdHolder.get());
        return saved;
    }

    /**
     * Applies one stock movement and returns it. {@code RECEIVE}/{@code ADJUST} add to
     * {@code quantityOnHand}; {@code DEDUCT}/{@code WASTE} subtract, and are rejected (§ concurrency
     * guard against overselling a stockroom that isn't there) if they would take the balance below
     * zero.
     */
    @Transactional
    public InventoryTransaction recordTransaction(UUID itemId, String typeRaw, BigDecimal quantity, String reason,
                                                    long itemVersion, UUID actorUserId) {
        InventoryItem item = itemRepository.findById(itemId).orElseThrow(() -> ApiException.notFound("Inventory item not found"));
        assertItemAccess(item, actorUserId);
        if (item.getVersion() != itemVersion) {
            throw new ObjectOptimisticLockingFailureException(InventoryItem.class, itemId);
        }
        InventoryTransactionType type = parseType(typeRaw);
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be greater than zero.");
        }
        AppUser user = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        if (user == null) {
            throw ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required to record a stock movement.");
        }

        BigDecimal newQuantity = switch (type) {
            case RECEIVE, ADJUST -> item.getQuantityOnHand().add(quantity);
            case DEDUCT, WASTE -> item.getQuantityOnHand().subtract(quantity);
        };
        if (newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("INSUFFICIENT_STOCK",
                    "This would take " + item.getName() + " below zero (" + item.getQuantityOnHand() + " " + item.getUnit() + " on hand).");
        }

        item.setQuantityOnHand(newQuantity);
        itemRepository.save(item);

        if ((type == InventoryTransactionType.DEDUCT || type == InventoryTransactionType.WASTE) && isLowStock(item)) {
            notificationService.create("LOW_STOCK", item.getName() + " is low on stock (" + newQuantity.stripTrailingZeros().toPlainString() + " " + item.getUnit() + " remaining).", item.getId());
        }

        InventoryTransaction saved = transactionRepository.save(InventoryTransaction.builder()
                .item(item)
                .type(type)
                .quantity(quantity)
                .resultingQuantity(newQuantity)
                .reason(reason)
                .recordedBy(user)
                .build());

        auditService.record(actorUserId, null, "InventoryItem", item.getId(), "STOCK_" + type.name(),
                null, quantity.toPlainString() + " " + item.getUnit() + " -> " + newQuantity, reason, CorrelationIdHolder.get());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<InventoryTransaction> listTransactions(UUID itemId, UUID actorUserId) {
        InventoryItem item = itemRepository.findById(itemId).orElseThrow(() -> ApiException.notFound("Inventory item not found"));
        assertItemAccess(item, actorUserId);
        return transactionRepository.findByItemIdOrderByCreatedAtDesc(itemId);
    }

    /** Round 14 (F3.1) - sets or clears (pass {@code null}) the item's {@code preferredSupplier},
     * used by {@code AutoReplenishmentScheduler} to group auto-generated draft POs. Deliberately a
     * separate method from {@link #updateItem} rather than an added parameter on it - keeps that
     * already-tested method's signature (and every existing call site, including {@code
     * InventoryServiceTest}) untouched. */
    @Transactional
    public InventoryItem setPreferredSupplier(UUID itemId, UUID supplierId, long expectedVersion, UUID actorUserId) {
        InventoryItem item = itemRepository.findById(itemId).orElseThrow(() -> ApiException.notFound("Inventory item not found"));
        assertItemAccess(item, actorUserId);
        if (item.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(InventoryItem.class, itemId);
        }
        Supplier supplier = supplierId == null ? null
                : supplierRepository.findById(supplierId).orElseThrow(() -> ApiException.notFound("Supplier not found"));
        // Bistrodesk branch-isolation release (requirement #3/#6): a supplier now belongs to
        // exactly one branch (see Supplier.branch's javadoc) - same "no ID/param manipulation
        // should ever leak cross-branch data" guard PurchaseOrderService#assertSupplierBranch
        // applies. Only enforced when the item itself has a real owning branch - a shared/
        // not-yet-assigned item (item.branch == null) has no branch to compare against.
        if (supplier != null && item.getBranch() != null && supplier.getBranch() != null
                && !supplier.getBranch().getId().equals(item.getBranch().getId())) {
            throw ApiException.badRequest("SUPPLIER_BRANCH_MISMATCH",
                    "Supplier " + supplier.getName() + " does not belong to this item's branch.");
        }
        item.setPreferredSupplier(supplier);
        InventoryItem saved = itemRepository.save(item);
        auditService.record(actorUserId, null, "InventoryItem", saved.getId(), "PREFERRED_SUPPLIER_SET", null,
                supplier == null ? "cleared" : supplier.getName(), null, CorrelationIdHolder.get());
        return saved;
    }

    /** Bistrodesk Phase 2: mirrors {@code OrderService#assertBranchAccess} exactly - a no-op for a
     * not-yet-assigned item (null branch, the shared/legacy bucket) or an unrestricted caller,
     * otherwise 404s a caller who isn't allowed at the item's branch. Every mutating entry point
     * above (update/recordTransaction/listTransactions/setPreferredSupplier) calls this so a
     * branch-restricted caller can't reach another branch's stock ledger by item id even though
     * {@link #listItems} already hides it from the catalog view. */
    private void assertItemAccess(InventoryItem item, UUID actorUserId) {
        Branch branch = item.getBranch();
        if (branch == null) {
            return;
        }
        AppUser requester = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        branchAccessService.assertAccess(requester, branch.getId());
    }

    private InventoryTransactionType parseType(String raw) {
        try {
            return InventoryTransactionType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw ApiException.badRequest("INVALID_TRANSACTION_TYPE", "Unknown inventory transaction type: " + raw);
        }
    }
}
