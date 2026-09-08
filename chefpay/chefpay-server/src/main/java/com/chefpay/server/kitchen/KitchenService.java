package com.chefpay.server.kitchen;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.KitchenStation;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.KitchenStationRepository;
import com.chefpay.core.repository.OrderItemRepository;
import com.chefpay.core.repository.OrderRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.orders.OrderDtos;
import com.chefpay.server.orders.OrderMapper;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kitchen Display queue + station configuration (Phase 3, requirement §16-§20). The queue is
 * deliberately built from {@link OrderItem} status, not {@link com.chefpay.core.domain.Order}
 * status: an order can have some lines READY and others still PREPARING, and each line needs its
 * own place in the kitchen's view (requirement §14's "independent per-item state" carried through
 * to the KDS, not flattened back to one order-level status).
 *
 * <p>Bistrodesk branch-isolation release: previously this whole class queried across the entire
 * install with no branch filter at all - the clearest cross-branch leak found in that audit (a
 * Branch A kitchen screen showed every branch's tickets). {@link #listQueue} now filters by branch
 * (see that method's own javadoc for how); {@link #advanceAllItems}/{@link #serveAllItems} assert
 * access via {@link #assertOrderAccess}, mirroring {@code InventoryService#assertItemAccess}'s exact
 * null-tolerant shape (a legacy order with no resolvable branch, or an unrestricted caller, is
 * always allowed through).
 */
@Service
@RequiredArgsConstructor
public class KitchenService {

    /** Lines still "in flight" from the kitchen's point of view - not yet SERVED/CANCELLED/VOIDED. */
    private static final Set<OrderItemStatus> ACTIVE_KITCHEN_STATUSES =
            EnumSet.of(OrderItemStatus.SENT, OrderItemStatus.ACCEPTED, OrderItemStatus.PREPARING, OrderItemStatus.READY);

    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final KitchenStationRepository stationRepository;
    private final RestaurantRepository restaurantRepository;
    private final OrderMapper orderMapper;
    private final AuditService auditService;
    private final WebSocketEventPublisher eventPublisher;
    private final BranchAccessService branchAccessService;
    private final AppUserRepository appUserRepository;

    /** Bistrodesk post-release fix: {@code branchId} (optional, access-checked when supplied) lets a
     * caller ask for one specific branch's queue explicitly. Omitted, this now resolves this
     * caller's ONE working branch first (default branch, single accessible branch, or - the common
     * single-branch install - this install's one and only branch), exactly mirroring {@code
     * TableController#list}'s and {@code OrderController#resolveAccessibleBranchIds}'s identical
     * fallback chain, and for the same reason: a Kitchen Display is inherently one physical screen
     * for one physical kitchen - there is no legitimate "every branch's tickets combined" view of it,
     * unlike a back-office report. Previously this filtered via {@link
     * BranchAccessService#accessibleBranchIds} alone, which returns {@code null} - no filter - for
     * exactly the unrestricted/multi-branch caller this bug report described ("kitchen display shows
     * other branches' orders"). Falls back to the old "every accessible branch" behavior only when a
     * single working branch is genuinely ambiguous ({@code BRANCH_REQUIRED}). */
    @Transactional(readOnly = true)
    public List<OrderDtos.OrderDto> listQueue(UUID branchId, UUID stationId, UUID actorUserId) {
        List<OrderItem> activeItems = orderItemRepository.findByStatusInOrderBySentAtAsc(ACTIVE_KITCHEN_STATUSES);

        if (stationId != null) {
            activeItems = activeItems.stream()
                    .filter(item -> item.getMenuItem().getStation() != null
                            && item.getMenuItem().getStation().getId().equals(stationId))
                    .toList();
        }

        Set<UUID> accessibleBranchIds = resolveBranchIds(branchId, resolveRequester(actorUserId));
        if (accessibleBranchIds != null) {
            activeItems = activeItems.stream().filter(item -> isVisible(item.getOrder(), accessibleBranchIds)).toList();
        }

        // Group by order while preserving the oldest-item-first order the query already gave us,
        // so the ticket for the longest-waiting item appears first on the KDS screen (FIFO).
        Map<UUID, Order> ordersById = new LinkedHashMap<>();
        Map<UUID, List<OrderItem>> itemsByOrder = new LinkedHashMap<>();
        for (OrderItem item : activeItems) {
            UUID orderId = item.getOrder().getId();
            ordersById.putIfAbsent(orderId, item.getOrder());
            itemsByOrder.computeIfAbsent(orderId, id -> new java.util.ArrayList<>()).add(item);
        }

        return ordersById.values().stream()
                .map(order -> orderMapper.toKitchenTicketDto(order, itemsByOrder.get(order.getId())))
                .toList();
    }

    /** A legacy order with no resolvable branch (see {@link Order#getEffectiveBranch()}) is always
     * visible - same "shared/legacy bucket" convention {@code InventoryService#assertItemAccess}
     * and {@code OrderService}'s own branch checks already use. */
    private boolean isVisible(Order order, Set<UUID> accessibleBranchIds) {
        Branch branch = order.getEffectiveBranch();
        return branch == null || accessibleBranchIds.contains(branch.getId());
    }

    private AppUser resolveRequester(UUID actorUserId) {
        return actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
    }

    /** Same fallback chain as {@code OrderController#resolveAccessibleBranchIds} - see
     * {@link #listQueue}'s own javadoc for why the Kitchen Display uses it too. */
    private Set<UUID> resolveBranchIds(UUID requestedBranchId, AppUser requester) {
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

    /** Mirrors {@code InventoryService#assertItemAccess} exactly: a no-op for a legacy order with
     * no resolvable branch or for an unrestricted caller, otherwise 404s a caller who isn't allowed
     * at the order's branch - so a branch-restricted kitchen user can't advance/serve another
     * branch's ticket by id even though {@link #listQueue} already hides it from the queue view. */
    private void assertOrderAccess(Order order, UUID actorUserId) {
        Branch branch = order.getEffectiveBranch();
        if (branch == null) {
            return;
        }
        branchAccessService.assertAccess(resolveRequester(actorUserId), branch.getId());
    }

    /**
     * "Receive All" / "Advance All" (requirement: kitchen staff shouldn't have to click every item
     * on a ticket one at a time) - moves every still-in-flight item on this order forward exactly
     * one step (SENT→ACCEPTED, ACCEPTED→PREPARING, PREPARING→READY, READY→SERVED), each in the
     * step it happens to currently be in, all inside one transaction/one version check rather than
     * the client firing N sequential single-item PATCHes (each of which would need its own version
     * check and could race against a concurrent change mid-sequence). Items with no forward step
     * (ADDED - not sent yet, or already terminal) are silently left alone rather than erroring the
     * whole batch.
     */
    @Transactional
    public Order advanceAllItems(UUID orderId, long expectedVersion, UUID actorUserId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        assertOrderAccess(order, actorUserId);
        if (order.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
        }

        LocalDateTime now = LocalDateTime.now();
        int advanced = 0;
        for (OrderItem item : order.getItems()) {
            OrderItemStatus next = nextKitchenStatus(item.getStatus());
            if (next == null || !item.getStatus().canTransitionTo(next)) {
                continue;
            }
            item.setStatus(next);
            switch (next) {
                case ACCEPTED -> item.setAcceptedAt(now);
                case PREPARING -> item.setStartedAt(now);
                case READY -> item.setReadyAt(now);
                case SERVED -> item.setServedAt(now);
                default -> { /* no timestamp for this transition */ }
            }
            advanced++;
        }
        if (advanced == 0) {
            throw ApiException.badRequest("NOTHING_TO_ADVANCE", "No items on this ticket are ready to move forward.");
        }

        Order saved = orderRepository.save(order);
        auditService.record(actorUserId, null, "Order", saved.getId(), "KITCHEN_ADVANCE_ALL", null,
                advanced + " item(s)", null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/kitchen", "ITEM_STATUS_CHANGED", saved.getId(), saved.getVersion(),
                Map.of("advancedCount", advanced));
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());
        return saved;
    }

    /**
     * Round 12 §6: the "Simple Serve All Workflow" configuration - one action per order that walks
     * every still-in-flight item straight through to SERVED (as many {@link #nextKitchenStatus}
     * steps as each item needs), instead of {@link #advanceAllItems}'s one-step-at-a-time batch.
     * Gated server-side on {@code Restaurant.kitchenServiceMode == "SIMPLE"} - a client can't reach
     * this shortcut just by not showing the detailed buttons; the restaurant must have actually
     * opted into the simplified workflow, same "server is the trust boundary" rule every other
     * configurable workflow in this codebase follows (see {@code OrderService#updateOrderStatus}'s
     * {@code kotOptionalEnabled} gate for the precedent this mirrors).
     */
    @Transactional
    public Order serveAllItems(UUID orderId, long expectedVersion, UUID actorUserId) {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
        if (!"SIMPLE".equals(restaurant.getKitchenServiceMode())) {
            throw ApiException.badRequest("SIMPLE_KITCHEN_MODE_DISABLED",
                    "Turn on the Simple Serve All kitchen workflow in Settings before using this action.");
        }

        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        assertOrderAccess(order, actorUserId);
        if (order.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
        }

        LocalDateTime now = LocalDateTime.now();
        int served = 0;
        for (OrderItem item : order.getItems()) {
            boolean movedThisItem = false;
            OrderItemStatus next = nextKitchenStatus(item.getStatus());
            while (next != null && item.getStatus().canTransitionTo(next)) {
                item.setStatus(next);
                switch (next) {
                    case ACCEPTED -> item.setAcceptedAt(now);
                    case PREPARING -> item.setStartedAt(now);
                    case READY -> item.setReadyAt(now);
                    case SERVED -> item.setServedAt(now);
                    default -> { /* no timestamp for this transition */ }
                }
                movedThisItem = true;
                next = nextKitchenStatus(item.getStatus());
            }
            if (movedThisItem && item.getStatus() == OrderItemStatus.SERVED) {
                served++;
            }
        }
        if (served == 0) {
            throw ApiException.badRequest("NOTHING_TO_SERVE", "No items on this ticket could be marked served.");
        }

        Order saved = orderRepository.save(order);
        auditService.record(actorUserId, null, "Order", saved.getId(), "KITCHEN_SERVE_ALL", null,
                served + " item(s)", null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/kitchen", "ITEM_STATUS_CHANGED", saved.getId(), saved.getVersion(),
                Map.of("servedCount", served));
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());
        return saved;
    }

    /** Mirrors {@code KitchenDisplayView}'s client-side NEXT_STATUS map - kept as a switch here
     * rather than a shared constant since the two live in different modules/languages-of-truth
     * (this is the server's authoritative state machine step, the client's map is purely a display
     * decision about which button to show). */
    private OrderItemStatus nextKitchenStatus(OrderItemStatus current) {
        return switch (current) {
            case SENT -> OrderItemStatus.ACCEPTED;
            case ACCEPTED -> OrderItemStatus.PREPARING;
            case PREPARING -> OrderItemStatus.READY;
            case READY -> OrderItemStatus.SERVED;
            default -> null;
        };
    }

    @Transactional(readOnly = true)
    public List<KitchenDtos.StationDto> listStations() {
        return stationRepository.findByActiveTrueOrderByDisplayOrderAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public KitchenDtos.StationDto createStation(String name, int displayOrder) {
        KitchenStation saved = stationRepository.save(KitchenStation.builder().name(name).displayOrder(displayOrder).build());
        return toDto(saved);
    }

    @Transactional
    public KitchenDtos.StationDto updateStation(UUID id, String name, Integer displayOrder, Boolean active, long version) {
        KitchenStation station = stationRepository.findById(id).orElseThrow(() -> ApiException.notFound("Kitchen station not found"));
        if (station.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(KitchenStation.class, id);
        }
        if (name != null) {
            station.setName(name);
        }
        if (displayOrder != null) {
            station.setDisplayOrder(displayOrder);
        }
        if (active != null) {
            station.setActive(active);
        }
        return toDto(stationRepository.save(station));
    }

    private KitchenDtos.StationDto toDto(KitchenStation s) {
        return new KitchenDtos.StationDto(s.getId(), s.getName(), s.getDisplayOrder(), s.isActive(), s.getVersion());
    }
}
