package com.chefpay.server.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record OrderItemDto(
            UUID id,
            UUID menuItemId,
            String menuItemName,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String status,
            boolean priority,
            String specialInstructions,
            String modifiersSummary,
            LocalDateTime sentAt,
            Long kotNumber,
            boolean directSale,
            long version
    ) {
    }

    public record OrderDto(
            UUID id,
            String orderNumber,
            String orderType,
            UUID tableId,
            String tableName,
            String customerName,
            String customerPhone,
            String waiterName,
            String cashierName,
            String status,
            String paymentStatus,
            String priority,
            List<OrderItemDto> items,
            BigDecimal subtotal,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            BigDecimal serviceChargeAmount,
            BigDecimal tipAmount,
            BigDecimal totalAmount,
            String notes,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            long version,
            UUID deliveryBoyId,
            String deliveryBoyName
    ) {
    }

    /** {@code tableId} is required for DINE_IN/QUICK_SERVICE, omitted for takeaway/delivery/phone/
     * online. {@code branchId} (Bistrodesk Phase 2) is required when {@code tableId} is omitted -
     * there is otherwise no way to know which branch a non-dine-in order belongs to (see {@code
     * Order#getBranch()}'s javadoc); when {@code tableId} IS given, {@code branchId} is optional and,
     * if supplied anyway, must match that table's own branch - see {@code
     * OrderService#openOrCreateOrder}'s javadoc. */
    public record CreateOrderRequest(
            @NotNull String orderType,
            UUID tableId,
            UUID branchId,
            String customerName,
            String customerPhone,
            String notes
    ) {
    }

    /** {@code portion}: null/anything other than "HALF" means the item's normal (full) price and
     * no {@code modifiersSummary} note; "HALF" only takes effect when the menu item actually has a
     * {@code halfPrice} configured (see {@code MenuItem#halfPrice}'s javadoc) - otherwise it's
     * silently ignored and the item is added at its normal price, so a stale/mismatched client
     * request can never charge a "half price" that doesn't actually exist. */
    public record AddItemRequest(
            @NotNull UUID menuItemId,
            @NotNull @Positive BigDecimal quantity,
            String specialInstructions,
            String portion,
            long orderVersion
    ) {
    }

    public record UpdateItemRequest(BigDecimal quantity, String specialInstructions, long orderVersion) {
    }

    /** Round 11 - "add customer" action on an already-open Dine In order. {@code customerName}/
     * {@code customerPhone}: null leaves that field unchanged; blank string ("") clears a
     * previously-set value - same convention {@code UpdateRestaurantRequest} uses elsewhere.
     *
     * <p>Bistrodesk Phase 7 (requirement #2): {@code customerId} is new - when supplied (a real hit
     * from the Customer directory search, or a customer just quick-created from the order screen),
     * the order is linked to that directory record via the real {@code Order.customer} FK (see its
     * javadoc), and {@code customerName}/{@code customerPhone} are IGNORED in favor of that
     * Customer's own name/phone - a directory record is the more authoritative source once one is
     * being attached, so a stale/conflicting pair of strings passed alongside it in the same call
     * can never silently win. Omit {@code customerId} entirely to keep today's inline-only
     * behavior (a guest with no directory record, or a plain name/phone correction). */
    public record UpdateCustomerRequest(String customerName, String customerPhone, UUID customerId, long orderVersion) {
    }

    public record CancelItemRequest(String reason, long orderVersion) {
    }

    public record UpdateOrderStatusRequest(@NotNull String status, String reason, long version) {
    }

    public record UpdateItemStatusRequest(@NotNull String status, String reason, long orderVersion) {
    }

    /** {@code deliveryBoyId} null clears the assignment. */
    public record AssignDeliveryBoyRequest(UUID deliveryBoyId, long orderVersion) {
    }

    /** UI Modernization Phase 1 follow-up - "Move Table" request body. See {@code
     * OrderService#moveTable}'s javadoc. */
    public record MoveTableRequest(@NotNull UUID tableId, long orderVersion) {
    }
}
