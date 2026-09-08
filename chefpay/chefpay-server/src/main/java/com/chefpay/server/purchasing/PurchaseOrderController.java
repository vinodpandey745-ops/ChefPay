package com.chefpay.server.purchasing;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.PurchaseOrder;
import com.chefpay.core.domain.PurchaseOrderItem;
import com.chefpay.core.domain.PurchaseOrderShareLog;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Purchase Order endpoints - Round 12 §12-§26. Every mutating action is permission-gated here
 * (§28: "permission checks happen at the backend/service layer, not just the UI"); branch
 * isolation (§23/§29) is enforced via the shared {@link BranchAccessService} rather than left to
 * the client to only ever ask for branches it's allowed to see.
 *
 * <p>Bistrodesk branch-isolation release: this controller used to carry its own private
 * {@code assertBranchAccess}/{@code filterToAccessibleBranches} reimplementation - the exact
 * precedent {@link BranchAccessService} was later generalized from - but never migrated onto the
 * shared bean, and its own reimplementation didn't honor {@link BranchAccessService#VIEW_ALL_BRANCHES}.
 * Now uses the shared service, and every lifecycle action below (submit/approve/reject/cancel/
 * close/share/share-log/document/receive) gets its own branch-access check - previously only
 * list/get/create/update/createFromSuggestions did, so a user restricted to Branch A could act on
 * a Branch B purchase order by id as long as they held the right role permission.
 *
 * <p>Bistrodesk Phase 4 (requirement #24): the whole controller also requires the
 * {@code PURCHASE_ORDERS} plan feature - see {@link RequiresFeature}'s javadoc.
 */
@RestController
@RequestMapping("/api/purchasing")
@RequiredArgsConstructor
@RequiresFeature("PURCHASE_ORDERS")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final ReplenishmentService replenishmentService;
    private final BranchAccessService branchAccessService;

    @GetMapping("/purchase-orders")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_VIEW') or hasAuthority('PURCHASE_ORDER_CREATE') or hasAuthority('PURCHASE_ORDER_APPROVE')")
    public ApiResponse<List<PurchaseOrderDtos.PurchaseOrderDto>> list(@RequestParam(required = false) UUID branchId,
                                                                       @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        if (branchId != null) {
            branchAccessService.assertAccess(requester, branchId);
            return ApiResponse.ok(purchaseOrderService.listPurchaseOrders(branchId).stream().map(this::toDto).toList());
        }
        List<PurchaseOrder> all = purchaseOrderService.listPurchaseOrders(null);
        return ApiResponse.ok(branchAccessService.filterToAccessibleBranches(requester, all, po -> po.getBranch().getId())
                .stream().map(this::toDto).toList());
    }

    @GetMapping("/purchase-orders/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_VIEW') or hasAuthority('PURCHASE_ORDER_CREATE') or hasAuthority('PURCHASE_ORDER_APPROVE')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        PurchaseOrder po = purchaseOrderService.getPurchaseOrder(id);
        branchAccessService.assertAccess(principal, po.getBranch().getId());
        return ApiResponse.ok(toDto(po));
    }

    @PostMapping("/purchase-orders")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_CREATE')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> create(@Valid @RequestBody PurchaseOrderDtos.CreatePurchaseOrderRequest request,
                                                                   @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, request.branchId());
        PurchaseOrder saved = purchaseOrderService.createPurchaseOrder(request.branchId(), request.supplierId(), request.notes(),
                request.items(), userId(principal));
        return ApiResponse.ok(toDto(saved));
    }

    @PatchMapping("/purchase-orders/{id}")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_MODIFY')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> update(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderDtos.UpdatePurchaseOrderRequest request,
                                                                   @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        PurchaseOrder existing = purchaseOrderService.getPurchaseOrder(id);
        branchAccessService.assertAccess(principal, existing.getBranch().getId());
        return ApiResponse.ok(toDto(purchaseOrderService.updatePurchaseOrder(id, request.supplierId(), request.notes(),
                request.items(), request.version(), userId(principal))));
    }

    @PostMapping("/purchase-orders/{id}/submit")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_CREATE') or hasAuthority('PURCHASE_ORDER_MODIFY')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> submit(@PathVariable UUID id, @RequestParam long version,
                                                                   @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, purchaseOrderService.getPurchaseOrder(id).getBranch().getId());
        return ApiResponse.ok(toDto(purchaseOrderService.submitForApproval(id, version, userId(principal))));
    }

    @PostMapping("/purchase-orders/{id}/approve")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_APPROVE')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> approve(@PathVariable UUID id, @RequestParam long version,
                                                                    @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, purchaseOrderService.getPurchaseOrder(id).getBranch().getId());
        return ApiResponse.ok(toDto(purchaseOrderService.approve(id, version, userId(principal))));
    }

    @PostMapping("/purchase-orders/{id}/reject")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_REJECT') or hasAuthority('PURCHASE_ORDER_APPROVE')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> reject(@PathVariable UUID id, @RequestBody PurchaseOrderDtos.RejectPurchaseOrderRequest request,
                                                                   @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, purchaseOrderService.getPurchaseOrder(id).getBranch().getId());
        return ApiResponse.ok(toDto(purchaseOrderService.reject(id, request.reason(), request.version(), userId(principal))));
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_CANCEL')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> cancel(@PathVariable UUID id, @RequestParam long version,
                                                                   @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, purchaseOrderService.getPurchaseOrder(id).getBranch().getId());
        return ApiResponse.ok(toDto(purchaseOrderService.cancel(id, version, userId(principal))));
    }

    @PostMapping("/purchase-orders/{id}/close")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_APPROVE') or hasAuthority('PURCHASE_ORDER_RECEIVE')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> close(@PathVariable UUID id, @RequestParam long version,
                                                                  @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, purchaseOrderService.getPurchaseOrder(id).getBranch().getId());
        return ApiResponse.ok(toDto(purchaseOrderService.close(id, version, userId(principal))));
    }

    @PostMapping("/purchase-orders/{id}/share")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_CREATE') or hasAuthority('PURCHASE_ORDER_APPROVE') or hasAuthority('PURCHASE_ORDER_MODIFY')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> share(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderDtos.ShareRequest request,
                                                                  @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, purchaseOrderService.getPurchaseOrder(id).getBranch().getId());
        return ApiResponse.ok(toDto(purchaseOrderService.shareWithSupplier(id, request.method(), request.recipient(), request.version(), userId(principal))));
    }

    @GetMapping("/purchase-orders/{id}/share-log")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_VIEW') or hasAuthority('PURCHASE_ORDER_CREATE') or hasAuthority('PURCHASE_ORDER_APPROVE')")
    public ApiResponse<List<PurchaseOrderDtos.ShareLogDto>> shareLog(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, purchaseOrderService.getPurchaseOrder(id).getBranch().getId());
        return ApiResponse.ok(purchaseOrderService.listShareLog(id).stream().map(this::toShareLogDto).toList());
    }

    @GetMapping("/purchase-orders/{id}/document")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_VIEW') or hasAuthority('PURCHASE_ORDER_CREATE') or hasAuthority('PURCHASE_ORDER_APPROVE')")
    public ApiResponse<String> document(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        PurchaseOrder po = purchaseOrderService.getPurchaseOrder(id);
        branchAccessService.assertAccess(principal, po.getBranch().getId());
        return ApiResponse.ok(purchaseOrderService.generateDocumentText(po));
    }

    @PostMapping("/purchase-orders/{id}/receive")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_RECEIVE')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> receive(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderDtos.ReceiveItemsRequest request,
                                                                    @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, purchaseOrderService.getPurchaseOrder(id).getBranch().getId());
        return ApiResponse.ok(toDto(purchaseOrderService.receiveItems(id, request.lines(), request.version(), userId(principal))));
    }

    @GetMapping("/replenishment-suggestions")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_CREATE') or hasAuthority('PURCHASE_ORDER_APPROVE') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<PurchaseOrderDtos.ReplenishmentSuggestionsResponse> replenishmentSuggestions() {
        return ApiResponse.ok(replenishmentService.generateSuggestions());
    }

    // ---- Round 14 (F2.2/F3.1) ----

    /** The confirmed-missing "create a draft PO from suggestions" action - see {@code
     * PurchaseOrderDtos.CreateDraftPoFromSuggestionsRequest}'s javadoc for why this is a thin,
     * pre-fill-only wrapper around the exact same {@code createPurchaseOrder} every manually-built
     * PO already goes through, rather than a new code path. */
    @PostMapping("/purchase-orders/from-suggestions")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_CREATE')")
    public ApiResponse<PurchaseOrderDtos.PurchaseOrderDto> createFromSuggestions(
            @Valid @RequestBody PurchaseOrderDtos.CreateDraftPoFromSuggestionsRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, request.branchId());
        PurchaseOrder saved = purchaseOrderService.createPurchaseOrder(request.branchId(), request.supplierId(),
                request.notes() == null ? "Created from replenishment suggestions" : request.notes(), request.items(), userId(principal));
        return ApiResponse.ok(toDto(saved));
    }

    /** Round 14 (F3.1) - the seasonality-aware forecast, same shape as the plain suggestions above
     * but weighted by day-of-week consumption history. Same permission audience as {@code
     * replenishmentSuggestions} above. */
    @GetMapping("/replenishment-suggestions/seasonal")
    @PreAuthorize("hasAuthority('PURCHASE_ORDER_CREATE') or hasAuthority('PURCHASE_ORDER_APPROVE') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<PurchaseOrderDtos.ReplenishmentSuggestionsResponse> seasonalSuggestions() {
        return ApiResponse.ok(replenishmentService.generateSeasonalSuggestions());
    }

    // ---- branch access enforcement (§23) is now delegated to BranchAccessService ----

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    private PurchaseOrderDtos.PurchaseOrderDto toDto(PurchaseOrder po) {
        List<PurchaseOrderDtos.PurchaseOrderItemDto> items = po.getItems().stream().map(this::toItemDto).toList();
        var total = items.stream().map(PurchaseOrderDtos.PurchaseOrderItemDto::lineTotal)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return new PurchaseOrderDtos.PurchaseOrderDto(po.getId(), po.getPoNumber(), po.getBranch().getId(), po.getBranch().getName(),
                po.getSupplier().getId(), po.getSupplier().getName(), po.getStatus().name(),
                po.getCreatedBy() == null ? null : po.getCreatedBy().getDisplayName(),
                po.getApprovedBy() == null ? null : po.getApprovedBy().getDisplayName(), po.getApprovedAt(),
                po.getRejectedBy() == null ? null : po.getRejectedBy().getDisplayName(), po.getRejectedAt(), po.getRejectionReason(),
                po.getSubmittedAt(), po.getClosedAt(), po.getNotes(), total, items, po.getCreatedAt(), po.getVersion(),
                purchaseOrderService.isWhatsAppConfigured(po));
    }

    private PurchaseOrderDtos.PurchaseOrderItemDto toItemDto(PurchaseOrderItem item) {
        return new PurchaseOrderDtos.PurchaseOrderItemDto(item.getId(), item.getInventoryItem().getId(), item.getInventoryItem().getName(),
                item.getInventoryItem().getUnit(), item.getOrderedQuantity(), item.getUnitPrice(), item.lineTotal(),
                item.getReceivedQuantity(), item.getAcceptedQuantity(), item.getDamagedQuantity(), item.getRejectedQuantity(),
                item.remainingQuantity(), item.getReceivingNotes(), item.getVersion());
    }

    private PurchaseOrderDtos.ShareLogDto toShareLogDto(PurchaseOrderShareLog log) {
        return new PurchaseOrderDtos.ShareLogDto(log.getId(), log.getMethod().name(), log.getRecipient(),
                log.getSentBy() == null ? null : log.getSentBy().getDisplayName(), log.getSentAt(), log.getStatus());
    }
}
