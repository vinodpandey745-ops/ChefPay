package com.chefpay.server.inventory;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.InventoryItem;
import com.chefpay.core.domain.InventoryTransaction;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stock item CRUD, low-stock listing, and the receive/adjust/deduct/waste transaction ledger -
 * Phase 5's Inventory slice (ARCHITECTURE.md §13). Gated by the {@code INVENTORY_VIEW}/
 * {@code INVENTORY_MANAGE} permission codes seeded ahead of time back in Phase 3's DataSeeder.
 *
 * <p>Bistrodesk branch-isolation release (requirement #3, user-confirmed decision: "strictly
 * branch specific, no shared option" - same call as Customer/Supplier): {@code InventoryItem.branch}
 * is now mandatory going forward, reversing Phase 2's "unassigned/shared item is a legitimate
 * resting state" design (see {@code InventoryItem#getBranch()}'s javadoc history). {@code
 * createItem} resolves the effective branch the same mandatory way Order/Customer/Supplier do -
 * {@link BranchAccessService#resolveEffectiveBranchId} - never leaving a new item branchless, and
 * {@code listItems}/{@code listLowStock} use the same resolveEffectiveBranchId-first-with-
 * BRANCH_REQUIRED-fallback chain {@code CustomerController#resolveBranchFilter} does, so an
 * unrestricted caller's own working branch is used by default instead of every branch's stock
 * merging together (the confirmed real-world bug: "inventory added at branch A is visible/editable
 * from branch B"). Any item created before this release that is still branchless gets backfilled
 * onto the install's oldest branch by {@code DataSeeder#ensureInventoryItemBranchBackfill} - see
 * its javadoc - so this fallback is a startup-only safety net, not a normal runtime path.
 *
 * <p>Bistrodesk Phase 4 (requirement #24): the whole controller now also requires the
 * {@code INVENTORY_MANAGEMENT} plan feature, on top of the permission checks above - see {@link
 * RequiresFeature}'s javadoc for why this is enforced server-side now, not just hidden client-side.
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@RequiresFeature("INVENTORY_MANAGEMENT")
public class InventoryController {

    private final InventoryService inventoryService;
    private final BranchAccessService branchAccessService;

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<List<InventoryDtos.ItemDto>> listItems(@RequestParam(required = false) UUID branchId,
                                                               @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> accessible = resolveAccessibleBranchIds(branchId, principal);
        return ApiResponse.ok(inventoryService.listItems(accessible).stream().map(this::toItemDto).toList());
    }

    @GetMapping("/items/low-stock")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<List<InventoryDtos.ItemDto>> listLowStock(@RequestParam(required = false) UUID branchId,
                                                                  @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> accessible = resolveAccessibleBranchIds(branchId, principal);
        return ApiResponse.ok(inventoryService.listLowStock(accessible).stream().map(this::toItemDto).toList());
    }

    @PostMapping("/items")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<InventoryDtos.ItemDto> createItem(@Valid @RequestBody InventoryDtos.CreateItemRequest request,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        UUID branchId = branchAccessService.resolveEffectiveBranchId(requester, request.branchId());
        InventoryItem saved = inventoryService.createItem(request.name(), request.unit(), request.openingQuantity(),
                request.reorderThreshold(), request.costPerUnit(), branchId, userId(principal));
        return ApiResponse.ok(toItemDto(saved));
    }

    @PatchMapping("/items/{id}")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<InventoryDtos.ItemDto> updateItem(@PathVariable UUID id,
                                                           @RequestBody InventoryDtos.UpdateItemRequest request,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        InventoryItem saved = inventoryService.updateItem(id, request.name(), request.unit(), request.reorderThreshold(),
                request.costPerUnit(), request.active(), request.version(), userId(principal));
        return ApiResponse.ok(toItemDto(saved));
    }

    // Round 12 §28 - INVENTORY_ADJUST is an additive, narrower alternative to INVENTORY_MANAGE for
    // this one action (a role can be granted "may adjust stock" without also getting item CRUD) -
    // every role that already holds INVENTORY_MANAGE keeps working exactly as before.
    @PostMapping("/items/{id}/transactions")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE') or hasAuthority('INVENTORY_ADJUST')")
    public ApiResponse<InventoryDtos.TransactionDto> recordTransaction(
            @PathVariable UUID id, @Valid @RequestBody InventoryDtos.RecordTransactionRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        InventoryTransaction saved = inventoryService.recordTransaction(id, request.type(), request.quantity(),
                request.reason(), request.itemVersion(), userId(principal));
        return ApiResponse.ok(toTransactionDto(saved));
    }

    @GetMapping("/items/{id}/transactions")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<List<InventoryDtos.TransactionDto>> listTransactions(@PathVariable UUID id,
                                                                             @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(inventoryService.listTransactions(id, userId(principal)).stream().map(this::toTransactionDto).toList());
    }

    // Round 14 (F3.1).
    @PatchMapping("/items/{id}/preferred-supplier")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<InventoryDtos.ItemDto> setPreferredSupplier(@PathVariable UUID id,
                                                                    @RequestBody InventoryDtos.SetPreferredSupplierRequest request,
                                                                    @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        InventoryItem saved = inventoryService.setPreferredSupplier(id, request.supplierId(), request.version(), userId(principal));
        return ApiResponse.ok(toItemDto(saved));
    }

    /** Same resolveEffectiveBranchId-first-with-BRANCH_REQUIRED-fallback chain {@code
     * CustomerController#resolveBranchFilter} uses (see this controller's own javadoc for why the
     * Inventory catalog needs it too): an omitted {@code branchId} narrows to this caller's own
     * working branch first, and only falls back to today's full {@code accessibleBranchIds}
     * behavior ({@code null} = unrestricted, no filter) when that's genuinely ambiguous. */
    private Set<UUID> resolveAccessibleBranchIds(UUID requestedBranchId, AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        if (requestedBranchId != null) {
            branchAccessService.assertAccess(requester, requestedBranchId);
            return Set.of(requestedBranchId);
        }
        try {
            return Set.of(branchAccessService.resolveEffectiveBranchId(requester, null));
        } catch (ApiException ex) {
            if (!"BRANCH_REQUIRED".equals(ex.getErrorCode())) {
                throw ex;
            }
            return branchAccessService.accessibleBranchIds(requester);
        }
    }

    private InventoryDtos.ItemDto toItemDto(InventoryItem i) {
        return new InventoryDtos.ItemDto(i.getId(), i.getName(), i.getUnit(), i.getQuantityOnHand(), i.getReorderThreshold(),
                i.getCostPerUnit(), inventoryService.isLowStock(i), i.isActive(), i.getVersion(),
                i.getPreferredSupplier() == null ? null : i.getPreferredSupplier().getId(),
                i.getPreferredSupplier() == null ? null : i.getPreferredSupplier().getName(),
                i.getBranch() == null ? null : i.getBranch().getId(),
                i.getBranch() == null ? null : i.getBranch().getName());
    }

    private InventoryDtos.TransactionDto toTransactionDto(InventoryTransaction t) {
        return new InventoryDtos.TransactionDto(t.getId(), t.getItem().getId(), t.getType().name(), t.getQuantity(),
                t.getResultingQuantity(), t.getReason(), t.getRecordedBy() == null ? null : t.getRecordedBy().getDisplayName(),
                t.getCreatedAt());
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
