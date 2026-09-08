package com.chefpay.server.orders;

import com.chefpay.core.domain.*;
import com.chefpay.core.repository.*;
import com.chefpay.core.service.AuditService;
import com.chefpay.core.service.NumberGeneratorService;
import com.chefpay.server.billing.TaxCalculationService;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.fraud.FraudRuleContext;
import com.chefpay.server.fraud.FraudRuleEngineService;
import com.chefpay.server.fraud.FraudRuleEventType;
import com.chefpay.server.notifications.NotificationService;
import com.chefpay.server.recipe.RecipeService;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the shared live order (requirement §2). Every mutating method here takes the
 * caller's last-known {@code Order.version} and fails fast with a 409 if it's stale (§22) -
 * concurrency correctness lives entirely in this one place rather than being re-implemented per
 * endpoint. Table status is kept in lock-step with order status ({@link #syncTableStatus}) so
 * the visual table matrix never drifts from what's actually happening to the order on it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    /**
     * Statuses at which an order is no longer "the active order for its table" - used both for
     * get-or-create (does this table already have one?) and for the general open-orders list.
     * Includes PAID alongside the two obviously-terminal statuses: nothing in this codebase's
     * order lifecycle ever actually advances an order from PAID to CLOSED (see
     * {@code BillingService}'s and {@code RestaurantTable#canTransitionTo}'s javadoc - PAID is
     * already the terminal "guest is done" state), and the table itself already flips back to
     * AVAILABLE the moment payment completes ({@link #syncTableStatus}). Leaving PAID out of this
     * list was a real bug: re-opening a table after a fully paid order left it still "occupied" by
     * that same old order (already-billed items impossible to remove) instead of starting a fresh
     * one, because the old order technically wasn't CLOSED or CANCELLED.
     */
    private static final List<OrderStatus> TABLE_INACTIVE_STATUSES =
            List.of(OrderStatus.PAID, OrderStatus.CLOSED, OrderStatus.CANCELLED);

    /** Order statuses in which new items may still be added - once billed, the order is frozen. */
    private static final List<OrderStatus> ITEM_ADDABLE_STATUSES = List.of(
            OrderStatus.DRAFT, OrderStatus.PLACED, OrderStatus.SENT_TO_KITCHEN,
            OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.SERVED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final RestaurantTableRepository tableRepository;
    private final MenuItemRepository menuItemRepository;
    private final AppUserRepository appUserRepository;
    private final NumberGeneratorService numberGeneratorService;
    private final AuditService auditService;
    private final WebSocketEventPublisher eventPublisher;
    private final NotificationService notificationService;
    private final DeliveryBoyRepository deliveryBoyRepository;
    private final RestaurantRepository restaurantRepository;
    private final FraudRuleEngineService fraudRuleEngineService;
    private final RecipeService recipeService;
    private final BranchRepository branchRepository;
    private final BranchAccessService branchAccessService;
    // Bistrodesk Phase 6 (bug #12, "tax not live in cart"): the running cart's own OrderDto never
    // carried a live taxAmount before this - see TaxCalculationService's javadoc and this class's
    // own applyLiveTax() for the fix.
    private final TaxCalculationService taxCalculationService;
    // Bistrodesk Phase 7 (requirement #2): resolves a real Customer directory record to link onto
    // an order - see updateCustomerDetails's javadoc.
    private final CustomerRepository customerRepository;

    /**
     * Get-or-create: if the target table already has an open order, that SAME order is returned
     * rather than a new one being created - this is the crux of "no duplicate orders" (§2/§63).
     *
     * <p>Bistrodesk Phase 2: {@code branchId} is new - see {@link Order#getBranch()}'s javadoc for
     * why this exists at all. When {@code tableId} is given, the table's OWN branch is authoritative
     * ({@code branchId}, if also supplied, must agree with it - a stale/incorrect client-supplied
     * value is rejected rather than silently overridden, never silently trusted over the real data).
     * When there is no table (a non-dine-in order), {@code branchId} is required - there is no other
     * way to know which branch is taking this order. Either way, the caller's actual access to the
     * resolved branch is checked here (not just left to the controller) via {@link
     * BranchAccessService}, since this is the one place that has both the authenticated actor and
     * the order's true branch in hand at the same time.
     */
    @Transactional
    public Order openOrCreateOrder(String orderTypeRaw, UUID tableId, UUID branchId, String customerName,
                                    String customerPhone, String notes, UUID actorUserId) {
        OrderType orderType = parseOrderType(orderTypeRaw);

        RestaurantTable table = null;
        if (tableId != null) {
            table = tableRepository.findById(tableId).orElseThrow(() -> ApiException.notFound("Table not found"));
            Optional<Order> existing = orderRepository.findFirstByTableIdAndStatusNotIn(tableId, TABLE_INACTIVE_STATUSES);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Branch branch = resolveOrderBranch(table, branchId);
        assertBranchAccess(branch, actorUserId);

        Order order = Order.builder()
                .orderNumber(numberGeneratorService.next("ORD"))
                .orderType(orderType)
                .table(table)
                .branch(branch)
                .customerName(customerName)
                .customerPhone(customerPhone)
                .notes(notes)
                .status(OrderStatus.PLACED)
                .build();

        if (actorUserId != null) {
            appUserRepository.findById(actorUserId).ifPresent(order::setWaiter);
        }

        Order saved = orderRepository.save(order);

        // Deliberately NOT flipping the table to ORDER_PLACED here. Tapping a table opens (or
        // get-or-creates) its order, but an order with zero items isn't actually occupying the
        // table yet - a waiter who taps a table and backs straight out without adding anything
        // used to leave it stuck "occupied" with nothing on it, blocking anyone else from seating
        // a real guest there. The table now only turns ORDER_PLACED once {@link #addItem} puts a
        // real item on the order (via {@link #syncTableStatus}), and reverts back to AVAILABLE if
        // every item is removed again before anything is sent to the kitchen.

        auditService.record(actorUserId, null, "Order", saved.getId(), "ORDER_CREATED", null,
                saved.getOrderNumber(), null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_CREATED", saved.getId(), saved.getVersion(),
                Map.of("orderNumber", saved.getOrderNumber(), "tableId", tableId == null ? "" : tableId.toString()));

        return saved;
    }

    /** Bistrodesk Phase 2: {@code actorUserId} is now required for the access check - see {@link
     * #assertOrderAccess}. Every caller (the GET-by-id endpoint, and this class's own internal
     * version-fallback lookup) now supplies it. */
    @Transactional(readOnly = true)
    public Order getOrder(UUID orderId, UUID actorUserId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        assertOrderAccess(order, actorUserId);
        return order;
    }

    @Transactional(readOnly = true)
    public List<Order> listOpenOrders() {
        return orderRepository.findByStatusNotInOrderByPriorityDescCreatedAtAsc(TABLE_INACTIVE_STATUSES);
    }

    /** Round 11: branch-scoped variant for a restaurant CHAIN's table matrix - see {@link
     * OrderRepository#findByStatusNotInAndBranch}'s javadoc for exactly what "scoped" means here.
     *
     * <p>Bistrodesk Phase 2: {@code branchIds} is the caller's ACCESSIBLE branch set (see {@code
     * BranchAccessService#accessibleBranchIds}) - {@code null} means unrestricted ("every branch",
     * the pre-Phase-2 behavior for a caller who never restricted a specific single branch); an empty
     * set would incorrectly mean "nothing" and is never passed here. A single explicit branch (the
     * caller picked one, or is restricted to exactly one) uses the simpler single-branch query; two
     * or more uses the IN-list variant. Access to an explicit, caller-REQUESTED single branch must
     * already have been checked by the caller (see {@code OrderController}) before this is called -
     * this method only filters, it does not itself re-validate a single requested id against the
     * caller's permissions (that would need the caller's identity, which a plain branch-id set
     * doesn't carry). */
    public List<Order> listOpenOrders(Set<UUID> branchIds) {
        if (branchIds == null) {
            return listOpenOrders();
        }
        if (branchIds.size() == 1) {
            return orderRepository.findByStatusNotInAndBranch(TABLE_INACTIVE_STATUSES, branchIds.iterator().next());
        }
        return orderRepository.findByStatusNotInAndBranchIn(TABLE_INACTIVE_STATUSES, branchIds);
    }

    /** UI Modernization Phase 1 follow-up - the web Orders Log originally only had {@link
     * #listOpenOrders()} to work with, which (correctly) hides anything closed/paid/cancelled, so
     * a genuine "order history" screen had nothing to show past today's still-open tickets. This
     * returns every order regardless of status, newest first, optionally narrowed by status and/or
     * branch, capped at {@code limit} - a real paginated audit log is future work; this is sized
     * for "show me recent history in one screen," the same scale every other list endpoint in this
     * project targets (see the KDS queue, table matrix, etc. - none of them paginate either).
     *
     * <p>Bistrodesk Phase 2: {@code branchIds} is the caller's accessible branch set (see {@link
     * #listOpenOrders(Set)}'s javadoc for the exact semantics of {@code null}), filtered via {@link
     * Order#getEffectiveBranch()} so a NEW non-table order (which now has a real branch, see {@link
     * Order#getBranch()}) is finally scoped correctly too, not just dine-in ones.
     *
     * <p>Bistrodesk Phase 7 (requirement #2): {@code customerId}, when supplied, narrows this to
     * orders actually linked (via the real {@code Order.customer} FK) to that one Customer directory
     * record - still branch-scoped like every other filter here. Replaces the Customers screen's old
     * client-side "guess from the last 200 orders restaurant-wide by phone/name" workaround with a
     * real, complete, exact per-customer query (not capped by how recent the restaurant's OVERALL
     * order volume happens to be). {@code null} means "don't filter by customer" - every existing
     * caller keeps working unchanged. */
    public List<Order> listOrderHistory(Set<UUID> branchIds, String statusFilter, UUID customerId, int limit) {
        Comparator<Order> byCreatedAtDesc = Comparator.comparing(Order::getCreatedAt).reversed();
        return orderRepository.findAll().stream()
                .filter(o -> branchIds == null || o.getEffectiveBranch() == null
                        || branchIds.contains(o.getEffectiveBranch().getId()))
                .filter(o -> statusFilter == null || statusFilter.isBlank()
                        || o.getStatus().name().equalsIgnoreCase(statusFilter))
                .filter(o -> customerId == null || (o.getCustomer() != null && customerId.equals(o.getCustomer().getId())))
                .sorted(byCreatedAtDesc)
                .limit(Math.max(1, Math.min(limit, 500)))
                .toList();
    }

    @Transactional
    public Order addItem(UUID orderId, UUID menuItemId, BigDecimal quantity, String specialInstructions,
                          String portion, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        if (!ITEM_ADDABLE_STATUSES.contains(order.getStatus())) {
            throw ApiException.conflict("ORDER_NOT_MODIFIABLE",
                    "Order " + order.getOrderNumber() + " is past billing and can no longer accept new items.");
        }
        MenuItem menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> ApiException.notFound("Menu item not found"));
        if (!menuItem.isAvailable() || !menuItem.isActive()) {
            throw ApiException.badRequest("ITEM_UNAVAILABLE", menuItem.getName() + " is currently unavailable.");
        }

        // "HALF" only actually applies when this item has a configured halfPrice - otherwise a
        // stale/mismatched request just silently falls back to the normal (full) price rather than
        // charging a "half price" that was never configured. See AddItemRequest's javadoc.
        BigDecimal unitPrice = menuItem.getPrice();
        String modifiersSummary = null;
        if ("HALF".equalsIgnoreCase(portion) && menuItem.getHalfPrice() != null) {
            unitPrice = menuItem.getHalfPrice();
            modifiersSummary = "Half";
        }

        OrderItem item = OrderItem.builder()
                .menuItem(menuItem)
                .quantity(quantity)
                .unitPriceSnapshot(unitPrice)
                .specialInstructions(specialInstructions)
                .modifiersSummary(modifiersSummary)
                .status(OrderItemStatus.ADDED)
                .build();
        order.addItem(item);
        applyLiveTax(order);
        // This is the moment the table actually becomes occupied (see the comment in
        // openOrCreateOrder above) - the very first real item landing on the order, not the tap
        // that opened it.
        syncTableStatus(order);
        // Order.items is a plain List (bag semantics - no @OrderColumn). Hibernate's flush-time
        // orphan-removal diff for bag collections identifies elements by database id, which means
        // it needs an id for every element CURRENTLY in the collection too, not just the ones
        // being removed - including a brand-new transient one we just added in memory. Without an
        // id yet, that lookup fails with "object references an unsaved transient instance" the
        // moment orderRepository.save(order) below triggers the flush. Explicitly persisting the
        // new item first assigns its id up front and sidesteps the bug entirely; it does not
        // cause a double-insert since the item stays the same managed instance inside
        // order.items, so the later save just sees it as already-persisted.
        orderItemRepository.save(item);
        Order saved = orderRepository.save(order);
        OrderItem savedItem = saved.getItems().get(saved.getItems().size() - 1);

        auditService.record(actorUserId, null, "OrderItem", savedItem.getId(), "ITEM_ADDED", null,
                menuItem.getName() + " x" + quantity, null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_ITEM_ADDED", saved.getId(), saved.getVersion(),
                Map.of("itemName", menuItem.getName(), "quantity", quantity));
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());

        return saved;
    }

    @Transactional
    public Order updateItem(UUID orderId, UUID itemId, BigDecimal newQuantity, String newInstructions, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        OrderItem item = findItem(order, itemId);
        if (item.getStatus() != OrderItemStatus.ADDED) {
            throw ApiException.conflict("ITEM_ALREADY_SENT",
                    "This item was already sent to the kitchen - cancel it and add a new one instead of editing in place.");
        }
        if (newQuantity != null) {
            item.setQuantity(newQuantity);
        }
        if (newInstructions != null) {
            item.setSpecialInstructions(newInstructions);
        }
        applyLiveTax(order);
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "OrderItem", item.getId(), "ITEM_MODIFIED", null, null, null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());
        return saved;
    }

    @Transactional
    public Order removeOrCancelItem(UUID orderId, UUID itemId, String reason, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        OrderItem item = findItem(order, itemId);
        boolean wasAlreadySent = item.getStatus() != OrderItemStatus.ADDED;

        if (item.getStatus() == OrderItemStatus.ADDED) {
            // orphanRemoval on Order.items handles the actual DELETE on flush - no explicit
            // repository call needed (and safer not to: it would race with orphanRemoval's own
            // delete of the same row).
            order.getItems().remove(item);
        } else {
            // Already sent to kitchen: this Phase 2 endpoint takes the direct permission-gated
            // cancel path (caller must hold ORDER_MODIFY) rather than routing through
            // CANCEL_REQUESTED - a kitchen-confirmed two-step cancel is Phase 3 KDS scope.
            item.setStatus(OrderItemStatus.CANCELLED);
            item.setCancelReason(reason);
        }
        applyLiveTax(order);
        // Mirror of the addItem sync: if that was the last active item, the table shouldn't stay
        // "occupied" by an order with nothing on it - drop it back to AVAILABLE so it's seatable
        // again. Only meaningful pre-kitchen (canTransitionTo already refuses this once the table
        // has moved on to PREPARING/READY/etc., which is correct - once the kitchen's involved
        // there's a real order in flight regardless of what's on it right now).
        boolean noActiveItemsLeft = order.getItems().stream()
                .noneMatch(i -> i.getStatus() != OrderItemStatus.CANCELLED && i.getStatus() != OrderItemStatus.VOIDED);
        if (noActiveItemsLeft && order.getTable() != null) {
            RestaurantTable table = order.getTable();
            if (table.getStatus() == TableStatus.ORDER_PLACED && table.canTransitionTo(TableStatus.AVAILABLE)) {
                table.setStatus(TableStatus.AVAILABLE);
                tableRepository.save(table);
                eventPublisher.publish("/topic/tables", "TABLE_STATUS_CHANGED", table.getId(), table.getVersion(),
                        Map.of("tableName", table.getName(), "status", table.getStatus().name()));
            }
        } else {
            syncTableStatus(order);
        }
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "OrderItem", itemId, "ITEM_REMOVED", item.getStatus().name(), null, reason, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_ITEM_REMOVED", saved.getId(), saved.getVersion(), Map.of("itemId", itemId));
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());

        // Round 13 (AI Backbone Addendum F1.5): SPLIT_CHECK_CASH_EXTRACTION only cares about a
        // cancel on an item that had already been sent (an ADDED-only removal can never be
        // "post-payment" - see that rule's javadoc). Defensive try/catch for the same reason as
        // every other fraud-engine hook: this action must succeed regardless of the rule engine.
        if (wasAlreadySent) {
            try {
                fraudRuleEngineService.evaluateEvent(new FraudRuleContext(FraudRuleEventType.ITEM_CANCELLED,
                        LocalDate.now(), saved, null, itemId, item.lineTotal(), actorUserId, null));
            } catch (Exception ex) {
                log.error("Fraud rule engine failed evaluating item cancel {} - continuing without it.", itemId, ex);
            }
        }
        return saved;
    }

    @Transactional
    public Order sendToKitchen(UUID orderId, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        // Direct-sale items (MenuItem#directSale - bottled drinks, packaged snacks, anything that
        // needs no kitchen prep/verification) are deliberately excluded here: they stay at ADDED
        // for the rest of the order's life, fully billable/removable exactly like any other line,
        // but never enter the kitchen workflow at all - see MenuItem#directSale's javadoc.
        List<OrderItem> pending = order.getItems().stream()
                .filter(i -> i.getStatus() == OrderItemStatus.ADDED)
                .filter(i -> !i.getMenuItem().isDirectSale())
                .toList();
        // An order made up ENTIRELY of direct-sale items (e.g. just "2x Coke") has nothing that
        // literally needs sending to the kitchen, but must still be allowed to advance past PLACED
        // - otherwise it can never reach SERVED/billing at all (NOTHING_TO_SEND would permanently
        // block it, since sendToKitchen is the only door out of PLACED). Only actually treat this as
        // an error if there is nothing un-sent at all, direct-sale or otherwise.
        boolean hasAnyAddedItems = order.getItems().stream().anyMatch(i -> i.getStatus() == OrderItemStatus.ADDED);
        if (!hasAnyAddedItems) {
            throw ApiException.badRequest("NOTHING_TO_SEND", "No new items to send to the kitchen.");
        }
        Long kotNumber = pending.isEmpty() ? null : numberGeneratorService.nextNumeric("KOT");
        LocalDateTime now = LocalDateTime.now();
        pending.forEach(i -> {
            i.setStatus(OrderItemStatus.SENT);
            i.setSentAt(now);
            i.setKotNumber(kotNumber);
        });

        // Round 14 (F2.1): real-time recipe-based stock deduction, the moment an item is actually
        // committed to the kitchen (not when it's merely added to the order - a waiter backing an
        // item back out before sending it should never have touched stock). Per-item try/catch:
        // RecipeService#deductForOrderItem already swallows a single ingredient line's failure, but
        // this is a second, coarser safety net in case an unexpected error escapes it anyway -
        // sending an order to the kitchen must never fail because of a stock-deduction problem.
        for (OrderItem item : pending) {
            try {
                recipeService.deductForOrderItem(item, actorUserId);
            } catch (Exception ex) {
                log.error("Recipe stock deduction failed for order item {} - order was still sent to the kitchen.", item.getId(), ex);
            }
        }

        if (order.getStatus().canTransitionTo(OrderStatus.SENT_TO_KITCHEN)) {
            order.setStatus(OrderStatus.SENT_TO_KITCHEN);
            order.setSentToKitchenAt(now);
        }
        syncTableStatus(order);
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "Order", saved.getId(), "ORDER_SENT_TO_KITCHEN", null,
                pending.size() + " item(s)", null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_SENT_TO_KITCHEN", saved.getId(), saved.getVersion(),
                Map.of("itemCount", pending.size()));
        eventPublisher.publish("/topic/kitchen", "ORDER_SENT_TO_KITCHEN", saved.getId(), saved.getVersion(),
                Map.of("orderNumber", saved.getOrderNumber()));
        return saved;
    }

    @Transactional
    public Order updateOrderStatus(UUID orderId, String newStatusRaw, String reason, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        OrderStatus newStatus = parseOrderStatus(newStatusRaw);
        if (!order.getStatus().canTransitionTo(newStatus)) {
            throw ApiException.conflict("INVALID_ORDER_TRANSITION",
                    "Order " + order.getOrderNumber() + " cannot move from " + order.getStatus() + " to " + newStatus);
        }
        // Round 11: "sent to kitchen option should be configurable... when physically verified then
        // can directly bill (without kitchen interference)". Every real kitchen dispatch goes through
        // sendToKitchen() below (which assigns a kotNumber and publishes to /topic/kitchen); this
        // generic status-PATCH path is the one door a client could otherwise use to slip an order
        // from PLACED straight into the SENT_TO_KITCHEN+ chain (see OrderTakingView's
        // "Bill Directly" action) WITHOUT ever actually dispatching a ticket - structurally legal per
        // canTransitionTo (it's still one step at a time), so it needs its own explicit opt-in gate
        // here rather than being silently always-available. Restaurants that haven't turned
        // Restaurant#kotOptionalEnabled on keep today's behavior: this transition is refused, and
        // "Send to Kitchen" (the dedicated endpoint) remains the only door out of PLACED.
        if (order.getStatus() == OrderStatus.PLACED && newStatus == OrderStatus.SENT_TO_KITCHEN) {
            boolean kotOptional = restaurantRepository.findAll().stream().findFirst()
                    .map(Restaurant::isKotOptionalEnabled).orElse(false);
            // POS patch (manual KOT print and order completion): a second, independent door through
            // this same gate - this order's own branch has opted into "print KOT manually without
            // sending to kitchen" (see Branch#manualKotPrintEnabled's javadoc), so it can also go
            // straight to billing without the separate, install-wide kotOptionalEnabled toggle.
            Branch effectiveBranch = order.getEffectiveBranch();
            boolean manualKotPrintBranch = effectiveBranch != null && effectiveBranch.isManualKotPrintEnabled();
            if (!kotOptional && !manualKotPrintBranch) {
                throw ApiException.badRequest("KOT_REQUIRED",
                        "This restaurant requires sending the order to the kitchen first - use \"Send to Kitchen\", "
                                + "or turn on \"Allow billing without sending to kitchen\" under Settings.");
            }
        }
        // Round 16: Restaurant#requireKitchenSyncForServed was added (default true) with a javadoc
        // describing exactly this gate but was never actually wired up anywhere - this was a real
        // bug, not just a missing feature: KitchenService#advanceAllItems/serveAllItems only ever
        // touch OrderItem.status (per-item, requirement §14), never Order.status, and nothing else
        // in this codebase advances Order.status to SERVED automatically. That left the ONLY door
        // to Order.status=SERVED as this generic status-PATCH, with no check at all that the
        // kitchen had actually finished every item - a waiter (or a client bug) could mark an order
        // Served, and therefore billable, with food still sitting in Preparing. Skipped entirely for
        // an order that was never sent to the kitchen in the first place (sentToKitchenAt == null,
        // i.e. the kotOptionalEnabled direct-billing path above) - there is no kitchen sync to
        // require when the kitchen was never involved.
        if (newStatus == OrderStatus.SERVED && order.getSentToKitchenAt() != null) {
            boolean requireSync = restaurantRepository.findAll().stream().findFirst()
                    .map(Restaurant::isRequireKitchenSyncForServed).orElse(true);
            if (requireSync) {
                boolean allServed = order.getItems().stream()
                        .filter(i -> i.getStatus() != OrderItemStatus.CANCELLED && i.getStatus() != OrderItemStatus.VOIDED)
                        .allMatch(i -> i.getStatus() == OrderItemStatus.SERVED);
                if (!allServed) {
                    throw ApiException.conflict("KITCHEN_NOT_SERVED",
                            "Not every item on order " + order.getOrderNumber() + " has been marked Served by the kitchen yet.");
                }
            }
        }
        OrderStatus previous = order.getStatus();
        order.setStatus(newStatus);

        if (newStatus == OrderStatus.CANCELLED) {
            notificationService.create("ORDER_CANCELLED", "Order " + order.getOrderNumber() + " was cancelled.", order.getId());
        }

        if (newStatus == OrderStatus.BILLED) {
            order.setBilledAt(LocalDateTime.now());
        }
        if (newStatus == OrderStatus.CLOSED || newStatus == OrderStatus.CANCELLED) {
            order.setClosedAt(LocalDateTime.now());
            if (order.getTable() != null) {
                order.getTable().setStatus(TableStatus.AVAILABLE);
                tableRepository.save(order.getTable());
                eventPublisher.publish("/topic/tables", "TABLE_STATUS_CHANGED", order.getTable().getId(),
                        order.getTable().getVersion(), Map.of("status", TableStatus.AVAILABLE.name()));
            }
        } else {
            syncTableStatus(order);
        }

        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "Order", saved.getId(), "ORDER_STATUS_CHANGED",
                previous.name(), newStatus.name(), reason, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_STATUS_CHANGED", saved.getId(), saved.getVersion(),
                Map.of("from", previous.name(), "to", newStatus.name()));
        return saved;
    }

    /** Round 11 - "in the dine in there should be add customer option". Unlike Delivery/Pick Up/
     * Online Order (which capture a customer at creation via {@link #openOrCreateOrder}), a Dine In
     * order is opened straight from a table tap with no customer prompt - this lets {@code
     * OrderTakingView} attach/edit name and phone on an ALREADY-OPEN order at any point before it's
     * closed, rather than only at creation. Blank string clears a previously-set value (same
     * null-means-unchanged / blank-means-clear convention {@code UpdateRestaurantRequest} already
     * uses) - a waiter correcting a typo doesn't need a separate "clear" action. Allowed at any
     * pre-terminal status (matches {@link #ITEM_ADDABLE_STATUSES} - if you can still touch the
     * order at all, you can still attach who it's for).
     *
     * <p>Bistrodesk Phase 7 (requirement #2): {@code customerId} is new. When supplied, this loads
     * that Customer directory record, links it onto the order via the real {@code Order.customer}
     * FK (populated for the first time by this method - see that field's own javadoc), and
     * OVERWRITES {@code customerName}/{@code customerPhone} from the Customer's own name/phone
     * rather than from the separately-passed string params - a directory record just being attached
     * is the more authoritative source, so a caller can't accidentally leave the inline strings
     * disagreeing with the record it just linked. When {@code customerId} is omitted, behavior is
     * completely unchanged from before this phase (inline strings only, no FK touched). */
    @Transactional
    public Order updateCustomerDetails(UUID orderId, String customerName, String customerPhone, UUID customerId,
                                        long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        if (order.getStatus().isTerminal()) {
            throw ApiException.conflict("ORDER_NOT_MODIFIABLE",
                    "Order " + order.getOrderNumber() + " is closed and can no longer be edited.");
        }
        if (customerId != null) {
            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> ApiException.notFound("Customer not found"));
            order.setCustomer(customer);
            order.setCustomerName(customer.getName());
            order.setCustomerPhone(customer.getPhone());
        } else {
            if (customerName != null) {
                order.setCustomerName(customerName.isBlank() ? null : customerName);
            }
            if (customerPhone != null) {
                order.setCustomerPhone(customerPhone.isBlank() ? null : customerPhone);
            }
        }
        Order saved = orderRepository.save(order);
        auditService.record(actorUserId, null, "Order", saved.getId(), "ORDER_CUSTOMER_UPDATED", null, null, null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());
        return saved;
    }

    @Transactional
    public Order updateItemStatus(UUID orderId, UUID itemId, String newStatusRaw, String reason, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        OrderItem item = findItem(order, itemId);
        OrderItemStatus newStatus = parseItemStatus(newStatusRaw);
        if (!item.getStatus().canTransitionTo(newStatus)) {
            throw ApiException.conflict("INVALID_ITEM_TRANSITION",
                    "Item cannot move from " + item.getStatus() + " to " + newStatus);
        }
        item.setStatus(newStatus);
        LocalDateTime now = LocalDateTime.now();
        switch (newStatus) {
            case ACCEPTED -> item.setAcceptedAt(now);
            case PREPARING -> item.setStartedAt(now);
            case READY -> item.setReadyAt(now);
            case SERVED -> item.setServedAt(now);
            case CANCELLED -> item.setCancelReason(reason);
            default -> { /* no timestamp for this transition */ }
        }
        applyLiveTax(order);
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "OrderItem", itemId, "ITEM_STATUS_CHANGED", null, newStatus.name(), reason, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/kitchen", "ITEM_STATUS_CHANGED", saved.getId(), saved.getVersion(),
                Map.of("itemId", itemId, "status", newStatus.name()));
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());
        return saved;
    }

    /** Assigns (or clears, if {@code deliveryBoyId} is null) the delivery rider on this order
     * (Round 9). Deliberately no restriction on the feature toggle or order type at this layer -
     * {@code Restaurant#deliveryBoyFeatureEnabled} only gates whether the JavaFX assignment control
     * is shown, same "server is only a trust boundary, not a UI-parity enforcer" rule every other
     * permission-gated toggle in this app follows (see {@code Restaurant}'s payment-method javadoc
     * for the same call). */
    @Transactional
    public Order assignDeliveryBoy(UUID orderId, UUID deliveryBoyId, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        DeliveryBoy deliveryBoy = null;
        if (deliveryBoyId != null) {
            deliveryBoy = deliveryBoyRepository.findById(deliveryBoyId)
                    .orElseThrow(() -> ApiException.notFound("Delivery boy not found"));
        }
        order.setDeliveryBoy(deliveryBoy);
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "Order", saved.getId(), "ORDER_DELIVERY_BOY_ASSIGNED", null,
                deliveryBoy == null ? "unassigned" : deliveryBoy.getName(), null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());
        return saved;
    }

    // ---- helpers ----

    /** Bistrodesk Phase 2: {@code actorUserId} is now required so every mutation funneling through
     * here (nearly every one in this class) gets the same branch-access check as a plain read - see
     * {@link #assertOrderAccess}. */
    private Order loadForUpdate(UUID orderId, long expectedVersion, UUID actorUserId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        assertOrderAccess(order, actorUserId);
        if (order.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
        }
        return order;
    }

    /** Bistrodesk Phase 2: resolves which {@link Branch} a NEW order belongs to - see {@link
     * Order#getBranch()}'s javadoc for why this exists. A table's own branch always wins when a
     * table is given; an explicitly-supplied {@code branchId} that disagrees with it is a client
     * bug/attack, not a hint to follow, so it is rejected rather than silently overridden. */
    private Branch resolveOrderBranch(RestaurantTable table, UUID requestedBranchId) {
        if (table != null) {
            Branch tableBranch = table.getFloor().getBranch();
            if (requestedBranchId != null && !tableBranch.getId().equals(requestedBranchId)) {
                throw ApiException.badRequest("BRANCH_MISMATCH",
                        "The selected table does not belong to the specified branch.");
            }
            return tableBranch;
        }
        if (requestedBranchId != null) {
            return branchRepository.findById(requestedBranchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        }
        // No table AND no explicit branchId: neither chefpay-web nor chefpay-javafx sends one for a
        // non-dine-in order on a single-branch install today (there's nothing for their branch
        // switcher to show), and this must not start rejecting every takeaway/delivery/phone/online
        // order on every existing single-branch install the moment this phase ships. Same
        // "the-one-branch-if-only-one-exists" fallback SubscriptionController already established.
        List<Branch> allBranches = branchRepository.findAll();
        if (allBranches.size() == 1) {
            return allBranches.get(0);
        }
        throw ApiException.badRequest("BRANCH_REQUIRED",
                "branchId is required for an order with no table (this restaurant has more than one branch).");
    }

    /** Bistrodesk Phase 2: enforces branch isolation (requirement #23/#29) for every order
     * read/mutation, not just the branch-scoped LIST endpoints - see {@code BranchAccessService}'s
     * javadoc. A null {@code branch} (an order with neither a direct branch nor a table - only
     * possible for a pre-Phase-2 legacy row, see {@link Order#getEffectiveBranch()}) is never itself
     * a violation: there is nothing to check access against, so it is allowed through exactly as it
     * always implicitly was. */
    private void assertBranchAccess(Branch branch, UUID actorUserId) {
        if (branch == null) {
            return;
        }
        AppUser requester = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        branchAccessService.assertAccess(requester, branch.getId());
    }

    private void assertOrderAccess(Order order, UUID actorUserId) {
        assertBranchAccess(order.getEffectiveBranch(), actorUserId);
    }

    private OrderItem findItem(Order order, UUID itemId) {
        return order.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Order item not found"));
    }

    /**
     * Bistrodesk Phase 6 (bug #12, "tax not live in cart") + POS patch ("service charge not shown
     * on cart"): {@code Order#recalculateTotals()} deliberately passes {@code taxAmount} and
     * {@code serviceChargeAmount} straight through unchanged (see its own javadoc) since nothing
     * used to compute a fresh value for either - so the plain OrderDto returned from every
     * pre-checkout item mutation showed a stuck ₹0 tax line and a stuck ₹0 service-charge line the
     * whole time an order was being built, only becoming correct the moment
     * {@code BillingService#generateBill} finally froze them at checkout. This recomputes both
     * live: tax from the exact same bracket math BillingService's own checkout preview
     * ({@code toBillDto}) and frozen bill ({@code generateBill}) already use -
     * {@link TaxCalculationService}; service charge from the identical
     * post-discount-taxable-base * {@code Restaurant#getServiceChargePercent()} / 100 (HALF_UP,
     * scale 2) formula those same two methods already use. Existing service-charge configuration
     * and calculation logic in {@code BillingService} is untouched by this - it only makes the same
     * number visible earlier, so the running cart can never show a tax or service-charge figure
     * that differs from what checkout is about to charge.
     *
     * <p>Must run AFTER the caller's own {@code order.recalculateTotals()} call has already updated
     * {@code subtotal} for the current item set (the taxable base below is computed from it), and
     * calls {@code recalculateTotals()} again itself so {@code totalAmount} folds in the
     * freshly-computed values - callers should call this in place of a bare
     * {@code order.recalculateTotals()} anywhere an item was added, changed, cancelled or removed.
     */
    private void applyLiveTax(Order order) {
        order.recalculateTotals();
        BigDecimal taxableBase = order.getSubtotal().subtract(order.getDiscountAmount());
        order.setTaxAmount(taxCalculationService.computeTaxAmount(order, taxableBase));
        BigDecimal serviceChargePercent = restaurantRepository.findAll().stream().findFirst()
                .map(Restaurant::getServiceChargePercent).orElse(BigDecimal.ZERO);
        BigDecimal serviceChargeAmount = taxableBase.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : taxableBase.multiply(serviceChargePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        order.setServiceChargeAmount(serviceChargeAmount);
        order.recalculateTotals();
    }

    /** UI Modernization Phase 1 follow-up - "Move Table" (guest relocates mid-meal; the existing
     * order and everything on it moves with them rather than requiring a cancel-and-recreate).
     * Reassigns {@code order.table} to {@code newTableId}, releases the OLD table back to
     * AVAILABLE (nothing else will touch it once the order's reference moves), and re-syncs the
     * NEW table to whatever status the order's current status maps to via {@link
     * #syncTableStatus}. Refuses to move onto a table that already has its own active order -
     * merging two orders' items together is a separate, not-yet-built feature, not something this
     * silently attempts. */
    @Transactional
    public Order moveTable(UUID orderId, UUID newTableId, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion, actorUserId);
        if (order.getTable() == null) {
            throw ApiException.badRequest("NOT_A_DINE_IN_ORDER", "Only dine-in orders can be moved between tables.");
        }
        if (TABLE_INACTIVE_STATUSES.contains(order.getStatus())) {
            throw ApiException.conflict("ORDER_NOT_MODIFIABLE",
                    "Order " + order.getOrderNumber() + " is already closed and can no longer be moved.");
        }
        RestaurantTable newTable = tableRepository.findById(newTableId)
                .orElseThrow(() -> ApiException.notFound("Table not found"));
        RestaurantTable oldTable = order.getTable();
        if (newTable.getId().equals(oldTable.getId())) {
            return order;
        }
        // Bistrodesk Phase 2: a caller must also be allowed to see the DESTINATION table's branch,
        // not just the order's current one - moving an order into a branch the caller can't
        // otherwise access would be a way around the branch-isolation check above.
        assertBranchAccess(newTable.getFloor().getBranch(), actorUserId);
        orderRepository.findFirstByTableIdAndStatusNotIn(newTableId, TABLE_INACTIVE_STATUSES).ifPresent(clashing -> {
            throw ApiException.conflict("TABLE_OCCUPIED", "Table " + newTable.getName() + " already has an active order.");
        });

        order.setTable(newTable);
        // Keep Order.branch in step with reality when a move crosses branches (rare in practice -
        // most multi-branch installs won't span floors across branches on one table matrix - but
        // "table wins" is the same rule resolveOrderBranch already established at creation time).
        order.setBranch(newTable.getFloor().getBranch());
        Order saved = orderRepository.save(order);

        if (oldTable.getStatus() != TableStatus.AVAILABLE && oldTable.canTransitionTo(TableStatus.AVAILABLE)) {
            oldTable.setStatus(TableStatus.AVAILABLE);
            tableRepository.save(oldTable);
            eventPublisher.publish("/topic/tables", "TABLE_STATUS_CHANGED", oldTable.getId(), oldTable.getVersion(),
                    Map.of("tableName", oldTable.getName(), "status", TableStatus.AVAILABLE.name()));
        }
        syncTableStatus(saved);

        auditService.record(actorUserId, null, "Order", saved.getId(), "ORDER_TABLE_MOVED",
                oldTable.getName(), newTable.getName(), null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_TABLE_MOVED", saved.getId(), saved.getVersion(),
                Map.of("fromTable", oldTable.getName(), "toTable", newTable.getName()));
        return saved;
    }

    /** Mirrors order status onto its table's status where the two enums overlap; see class javadoc. */
    private void syncTableStatus(Order order) {
        if (order.getTable() == null) {
            return;
        }
        TableStatus mapped = switch (order.getStatus()) {
            case DRAFT, PLACED -> TableStatus.ORDER_PLACED;
            case SENT_TO_KITCHEN, ACCEPTED, PREPARING -> TableStatus.PREPARING;
            case READY -> TableStatus.READY;
            case SERVED -> TableStatus.OCCUPIED;
            case BILL_REQUESTED -> TableStatus.BILL_REQUESTED;
            case BILLED, PAYMENT_PENDING -> TableStatus.PAYMENT_PENDING;
            case PAID, CLOSED, CANCELLED -> TableStatus.AVAILABLE;
        };
        RestaurantTable table = order.getTable();
        if (table.getStatus() != mapped && table.canTransitionTo(mapped)) {
            table.setStatus(mapped);
            tableRepository.save(table);
            eventPublisher.publish("/topic/tables", "TABLE_STATUS_CHANGED", table.getId(), table.getVersion(),
                    Map.of("tableName", table.getName(), "status", mapped.name()));
        }
    }

    private OrderType parseOrderType(String raw) {
        try {
            return OrderType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_ORDER_TYPE", "Unknown order type: " + raw);
        }
    }

    private OrderStatus parseOrderStatus(String raw) {
        try {
            return OrderStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown order status: " + raw);
        }
    }

    private OrderItemStatus parseItemStatus(String raw) {
        try {
            return OrderItemStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown item status: " + raw);
        }
    }
}
