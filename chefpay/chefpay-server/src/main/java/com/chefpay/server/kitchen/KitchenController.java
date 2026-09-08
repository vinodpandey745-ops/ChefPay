package com.chefpay.server.kitchen;

import com.chefpay.core.domain.Order;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.orders.OrderDtos;
import com.chefpay.server.orders.OrderMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Kitchen Display System surface (Phase 3, requirement §16-§20). Item status transitions
 * themselves stay on {@code PATCH /api/orders/{id}/items/{itemId}/status} (OrderController) -
 * there's only one place that owns the {@code OrderItemStatus} state machine (§8/§10), the KDS
 * just calls the same endpoint order-taking already uses. This controller only adds what's
 * genuinely kitchen-specific: the cross-order queue view, and station configuration.
 */
@RestController
@RequestMapping("/api/kitchen")
@RequiredArgsConstructor
public class KitchenController {

    private final KitchenService kitchenService;
    private final OrderMapper orderMapper;

    /** Bistrodesk post-release fix: {@code branchId} is new - see {@code KitchenService#listQueue}'s
     * own javadoc for why the queue previously showed every branch's tickets combined to any
     * unrestricted/multi-branch caller (confirmed real-world regression: "kitchen display shows other
     * branches' orders"). Optional and access-checked exactly like every other branch-aware endpoint
     * in this codebase - omitted, the service resolves this caller's own working branch itself. */
    @GetMapping("/queue")
    @PreAuthorize("hasAuthority('KITCHEN_VIEW') or hasAuthority('KITCHEN_UPDATE')")
    public ApiResponse<List<OrderDtos.OrderDto>> queue(@RequestParam(required = false) UUID branchId,
                                                        @RequestParam(required = false) UUID stationId,
                                                        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(kitchenService.listQueue(branchId, stationId, principal == null ? null : principal.userId()));
    }

    /** "Receive All" / "Advance All" - moves every still-in-flight item on this ticket forward one
     * step in a single call; see {@code KitchenService#advanceAllItems}'s javadoc. */
    @PostMapping("/orders/{orderId}/advance-all")
    @PreAuthorize("hasAuthority('KITCHEN_UPDATE')")
    public ApiResponse<OrderDtos.OrderDto> advanceAll(@PathVariable UUID orderId, @RequestParam long version,
                                                       @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> kitchenService.advanceAllItems(orderId, version,
                principal == null ? null : principal.userId()));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    /** Round 12 §6 "Simple Serve All Workflow" - see {@code KitchenService#serveAllItems}'s javadoc
     * for the server-side {@code kitchenServiceMode == "SIMPLE"} gate this relies on. */
    @PostMapping("/orders/{orderId}/serve-all")
    @PreAuthorize("hasAuthority('KITCHEN_UPDATE')")
    public ApiResponse<OrderDtos.OrderDto> serveAll(@PathVariable UUID orderId, @RequestParam long version,
                                                     @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Order order = withLockRetry(() -> kitchenService.serveAllItems(orderId, version,
                principal == null ? null : principal.userId()));
        return ApiResponse.ok(orderMapper.toDto(order));
    }

    /** Same SQLite single-writer retry as {@code OrderController#withLockRetry} - kitchen staff and
     * floor staff mutate orders concurrently just as often here, so this batch endpoint is just as
     * exposed to transient {@link CannotAcquireLockException} write-lock contention. Retries only
     * that specific transient exception; a real 409 (stale version, invalid transition) always
     * surfaces immediately. */
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

    @GetMapping("/stations")
    @PreAuthorize("hasAuthority('KITCHEN_VIEW') or hasAuthority('KITCHEN_UPDATE') or hasAuthority('MENU_MANAGE')")
    public ApiResponse<List<KitchenDtos.StationDto>> stations() {
        return ApiResponse.ok(kitchenService.listStations());
    }

    @PostMapping("/stations")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<KitchenDtos.StationDto> createStation(@Valid @RequestBody KitchenDtos.CreateStationRequest request) {
        return ApiResponse.ok(kitchenService.createStation(request.name(), request.displayOrder()));
    }

    @PatchMapping("/stations/{id}")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<KitchenDtos.StationDto> updateStation(@PathVariable UUID id, @RequestBody KitchenDtos.UpdateStationRequest request) {
        return ApiResponse.ok(kitchenService.updateStation(id, request.name(), request.displayOrder(), request.active(), request.version()));
    }
}
