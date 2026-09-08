package com.chefpay.server.orders;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Order;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.common.IdempotencyService;
import com.chefpay.server.kot.KotTicketService;
import com.chefpay.server.subscription.RequiresFeature;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * REST surface for the shared live order (requirement §2/§58). Every mutating endpoint takes the
 * caller's last-known {@code orderVersion} in the request body and returns a 409
 * {@code VERSION_CONFLICT} (via {@code OrderService}/{@code GlobalExceptionHandler}) rather than
 * silently applying a stale write - clients are expected to refetch and retry on that response.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final IdempotencyService idempotencyService;
    private final BranchAccessService branchAccessService;
    private final KotTicketService kotTicketService;

    /** Round 11: {@code branchId} is optional - see {@code TableController#list}'s identical
     * javadoc.
     *
     * <p>Bistrodesk Phase 2: an explicitly-requested {@code branchId} is now access-checked (a
     * caller can no longer see another branch's open orders just by passing its id); omitted, this
     * now defaults to the caller's OWN accessible branches (via {@link BranchAccessService}) rather
     * than unconditionally "everything" - {@code null} from {@link BranchAccessService
     * #accessibleBranchIds} still means unrestricted for a caller who genuinely has no branch
     * restriction configured, so a single-branch install sees no behavior change at all. */
    @GetMapping
    @PreAuthorize("hasAuthority('TABLE_VIEW') or hasAuthority('ORDER_CREATE') or hasAuthority('KITCHEN_VIEW')")
    public ApiResponse<List<OrderDtos.OrderDto>> listOpen(@RequestParam(required = false) UUID branchId,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> effectiveBranchIds = resolveAccessibleBranchIds(branchId, principal);
        return ApiResponse.ok(orderService.listOpenOrders(effectiveBranchIds).stream().map(orderMapper::toDto).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TABLE_VIEW') or hasAuthority('ORDER_CREATE') or hasAuthority('KITCHEN_VIEW')")
    public ApiResponse<OrderDtos.OrderDto> get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(orderMapper.toDto(orderService.getOrder(id, userId(principal))));
    }

    /** UI Modernization Phase 1 follow-up - {@link #listOpen} deliberately only ever returns
     * open orders (requirement-driven, not an oversight), so the web Orders Log had no way to show
     * genuine history. Separate endpoint rather than a flag on {@code listOpen} so the two stay
     * easy to reason about independently: this one is explicitly "everything, newest first,"
     * capped at {@code limit} (default 100, max 500 - see {@code OrderService#listOrderHistory}'s
     * javadoc on why this isn't paginated). Same Bistrodesk Phase 2 branch-access defaulting as
     * {@link #listOpen}.
     *
     * <p>Bistrodesk Phase 7 (requirement #2): {@code customerId} is new - narrows this to just the
     * orders actually linked (via the real {@code Order.customer} FK) to one Customer directory
     * record, still branch-scoped like every other filter here. Replaces the Customers screen's old
     * client-side "match the last 200 orders restaurant-wide by phone/name" workaround (see {@code
     * CustomersPage}'s own comment on why that was fragile and incomplete for a customer with more
     * history than that) with a real, complete, exact query. */
    @GetMapping("/history")
    @PreAuthorize("hasAuthority('TABLE_VIEW') or hasAuthority('ORDER_CREATE') or hasAuthority('KITCHEN_VIEW') or hasAuthority('BILLING_MANAGE')")
    public ApiResponse<List<OrderDtos.OrderDto>> history(@RequestParam(required = false) UUID branchId,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) UUID customerId,
                                                           @RequestParam(required = false, defaultValue = "100") int limit,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> effectiveBranchIds = resolveAccessibleBranchIds(branchId, principal);
        return ApiResponse.ok(orderService.listOrderHistory(effectiveBranchIds, status, customerId, limit).stream().map(orderMapper::toDto).toList());
    }

    /** Resolves the effective branch-id set to filter by: an explicit, access-checked single
     * branch when the caller asked for one; otherwise this caller's ONE working branch (their
     * configured default, their one accessible branch, or - the common single-branch install - the
     * install's one and only branch), mirroring {@code TableController#list}'s exact fallback chain
     * and for the same reason: {@code /orders} (open) and {@code /orders/history} are both
     * POS/kitchen-facing operational screens (order taking, the Orders Log, Kitchen/Dashboard's
     * "recent activity"), not a back-office cross-branch report - so a multi-branch or
     * {@code VIEW_ALL_BRANCHES} caller working at one physical terminal must see only that branch's
     * orders by default, not every branch's combined (Bistrodesk post-release fix - this previously
     * fell straight to {@link BranchAccessService#accessibleBranchIds}, which is {@code null} -
     * unfiltered - for exactly that caller, and was the confirmed cause of "orders/kitchen show every
     * branch's data"). Only when a single working branch is genuinely ambiguous (an unrestricted
     * caller with no default branch on a multi-branch install - {@code BRANCH_REQUIRED}) does this
     * fall back to the previous "every accessible branch" behavior. A single-branch install or a
     * caller who already passes an explicit {@code branchId} sees no change either way. */
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

    /**
     * Get-or-create for a table (§2: opening an already-open table's order returns the SAME
     * order, never a duplicate). Also accepts an {@code Idempotency-Key} header so a client
     * retry after a dropped response can't create two orders either.
     *
     * <p>Bistrodesk Phase 2: {@code request.branchId()} is new (required for a non-table order,
     * ignored/cross-checked against the table's own branch otherwise) - see {@code
     * OrderService#openOrCreateOrder}'s javadoc for the full resolution/access-check logic, which
     * deliberately lives there (not here) since it needs the table lookup this controller doesn't do.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public ApiResponse<OrderDtos.OrderDto> open(@Valid @RequestBody OrderDtos.CreateOrderRequest request,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                 @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        OrderDtos.OrderDto dto = idempotencyService.execute("CREATE_ORDER", idempotencyKey, OrderDtos.OrderDto.class, () -> {
            Order order = orderService.openOrCreateOrder(request.orderType(), request.tableId(), request.branchId(),
                    request.customerName(), request.customerPhone(), request.notes(), userId(principal));
            return orderMapper.toDto(order);
        });
        return ApiResponse.ok(dto);
    }

    @PostMapping("/{id}/items")
    @PreAuthorize("hasAuthority('ORDER_CREATE') or hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<OrderDtos.OrderDto> addItem(@PathVariable UUID id, @Valid @RequestBody OrderDtos.AddItemRequest request,
                                                    @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> orderService.addItem(id, request.menuItemId(), request.quantity(),
                request.specialInstructions(), request.portion(), request.orderVersion(), userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    @PatchMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<OrderDtos.OrderDto> updateItem(@PathVariable UUID id, @PathVariable UUID itemId,
                                                       @RequestBody OrderDtos.UpdateItemRequest request,
                                                       @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> orderService.updateItem(id, itemId, request.quantity(), request.specialInstructions(),
                request.orderVersion(), userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<OrderDtos.OrderDto> removeItem(@PathVariable UUID id, @PathVariable UUID itemId,
                                                       @RequestBody(required = false) OrderDtos.CancelItemRequest request,
                                                       @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        String reason = request == null ? null : request.reason();
        long version = request == null ? currentVersionFallback(id, principal) : request.orderVersion();
        Order order = withLockRetry(() -> orderService.removeOrCancelItem(id, itemId, reason, version, userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    @PostMapping("/{id}/send-to-kitchen")
    @PreAuthorize("hasAuthority('ORDER_CREATE') or hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<OrderDtos.OrderDto> sendToKitchen(@PathVariable UUID id, @RequestParam long version,
                                                          @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> orderService.sendToKitchen(id, version, userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    /** POS patch (manual KOT print and order completion): an alternative to {@link
     * #sendToKitchen} for a branch that has turned on {@code Branch#manualKotPrintEnabled} - prints
     * a kitchen ticket from the order's current items WITHOUT sending anything to the kitchen (no
     * KOT number assigned, items stay ADDED, the order never reaches the KDS). Same permission gate
     * as sendToKitchen since it's the same cashier action just choosing a different path; the actual
     * enable/disable check lives in {@code KotTicketService#buildManualKotText}, which rejects with
     * 400 for any branch that hasn't opted in. */
    @GetMapping("/{id}/kot-text")
    @PreAuthorize("hasAuthority('ORDER_CREATE') or hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<String> manualKotText(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(kotTicketService.buildManualKotText(id, userId(principal)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ORDER_MODIFY') or hasAuthority('KITCHEN_UPDATE') or hasAuthority('BILLING_MANAGE')")
    public ApiResponse<OrderDtos.OrderDto> updateStatus(@PathVariable UUID id, @Valid @RequestBody OrderDtos.UpdateOrderStatusRequest request,
                                                         @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> orderService.updateOrderStatus(id, request.status(), request.reason(), request.version(), userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    /** Round 11 - "add customer" action for Dine In orders (which, unlike Delivery/Pick Up/Online,
     * have no customer-capture step at creation - see {@code OrderService#updateCustomerDetails}'s
     * javadoc). Same permission gate as {@link #addItem} - anyone who can touch this order's items
     * can also attach who it's for. */
    @PatchMapping("/{id}/customer")
    @PreAuthorize("hasAuthority('ORDER_CREATE') or hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<OrderDtos.OrderDto> updateCustomer(@PathVariable UUID id, @Valid @RequestBody OrderDtos.UpdateCustomerRequest request,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> orderService.updateCustomerDetails(id, request.customerName(), request.customerPhone(),
                request.customerId(), request.orderVersion(), userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    @PatchMapping("/{id}/items/{itemId}/status")
    @PreAuthorize("hasAuthority('KITCHEN_UPDATE') or hasAuthority('ORDER_MODIFY')")
    public ApiResponse<OrderDtos.OrderDto> updateItemStatus(@PathVariable UUID id, @PathVariable UUID itemId,
                                                             @Valid @RequestBody OrderDtos.UpdateItemStatusRequest request,
                                                             @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> orderService.updateItemStatus(id, itemId, request.status(), request.reason(), request.orderVersion(), userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    /** Assign (or clear, if {@code deliveryBoyId} is null) the rider for this order (Round 9) -
     * gated on the same permission as any other order field edit rather than requiring the new
     * {@code DELIVERY_MANAGE} permission, since assigning a rider to an order is an order-taking/
     * front-of-house action, not roster management. */
    /** UI Modernization Phase 1 follow-up - "Move Table" from the web Tables view's quick-actions
     * panel (a guest relocating mid-meal). See {@code OrderService#moveTable}'s javadoc. */
    @PatchMapping("/{id}/table")
    @PreAuthorize("hasAuthority('ORDER_MODIFY') or hasAuthority('TABLE_MANAGE')")
    public ApiResponse<OrderDtos.OrderDto> moveTable(@PathVariable UUID id, @Valid @RequestBody OrderDtos.MoveTableRequest request,
                                                      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> orderService.moveTable(id, request.tableId(), request.orderVersion(), userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    /** Bistrodesk branch-isolation release (requirement #5's confirmed enforcement gap): the roster
     * CRUD ({@code DeliveryBoyController}) is class-level gated on {@code DELIVERY_MANAGEMENT}, but
     * this POS-facing action of attaching a rider to a live order had no feature gate at all - a
     * branch whose plan excludes delivery management could still fully use delivery orders as long
     * as at least one {@code DeliveryBoy} row already existed (e.g. carried over from before a
     * downgrade). Method-level {@code @RequiresFeature} (not class-level - every other action on
     * this controller is ungated/differently-gated) closes that gap the same way {@code
     * ThemeController#update}'s method-level annotation does. */
    @PatchMapping("/{id}/delivery-boy")
    @PreAuthorize("hasAuthority('ORDER_MODIFY')")
    @RequiresFeature("DELIVERY_MANAGEMENT")
    public ApiResponse<OrderDtos.OrderDto> assignDeliveryBoy(@PathVariable UUID id,
                                                              @RequestBody OrderDtos.AssignDeliveryBoyRequest request,
                                                              @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> orderService.assignDeliveryBoy(id, request.deliveryBoyId(), request.orderVersion(), userId(principal)));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    /** DELETE with no body still needs a version to concurrency-check against; fetch current as a last resort. */
    private long currentVersionFallback(UUID orderId, AuthenticatedPrincipal principal) {
        return orderService.getOrder(orderId, userId(principal)).getVersion();
    }

    /**
     * SQLite is single-writer - two terminals committing genuinely concurrent order mutations at
     * the same moment can still collide on the database file lock even with the datasource's
     * {@code busy_timeout} PRAGMA (see {@code application.yml}'s dev profile comment on that
     * setting), surfacing as {@link CannotAcquireLockException} (reported in practice as
     * "[SQLITE_BUSY] The database file is locked" reaching the client as an opaque 500 with no
     * indication it was transient and safe to just retry). The retry has to live here rather than
     * inside {@code OrderService} itself: {@code @Transactional} only starts a fresh transaction
     * when the call re-enters this bean's Spring proxy from outside, so a retry loop inside
     * {@code OrderService} calling itself would just keep reusing the same already-failed
     * transaction. Deliberately only retries {@link CannotAcquireLockException} (a transient lock
     * contention) - never {@code ObjectOptimisticLockingFailureException} (a real version conflict
     * the caller must see as a 409 and decide how to resolve, not something to silently paper over
     * by replaying the same stale-version request).
     */
    private <T> T withLockRetry(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (CannotAcquireLockException ex) {
                attempt++;
                if (attempt >= 4) {
                    throw ex;
                }
                try {
                    Thread.sleep(40L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }
}
