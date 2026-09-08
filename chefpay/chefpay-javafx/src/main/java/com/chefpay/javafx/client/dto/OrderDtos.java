package com.chefpay.javafx.client.dto;

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

    /** {@code branchId} (Bistrodesk Phase 2): required by the server for a non-table order (see
     * chefpay-server's {@code OrderService#openOrCreateOrder} javadoc) - pass {@code
     * SessionStore.get().getCurrentBranchId()}. Null is fine when {@code tableId} is set (the
     * table's own branch wins server-side) or on a single-branch install (the server falls back to
     * "the one branch" when nothing else is specified). */
    public record CreateOrderRequest(String orderType, UUID tableId, UUID branchId, String customerName, String customerPhone, String notes) {
    }

    public record AddItemRequest(UUID menuItemId, BigDecimal quantity, String specialInstructions, String portion, long orderVersion) {
    }

    public record UpdateItemRequest(BigDecimal quantity, String specialInstructions, long orderVersion) {
    }

    /** Round 11 - "add customer" action on an already-open Dine In order. Null leaves that field
     * unchanged; blank ("") clears a previously-set value. */
    public record UpdateCustomerRequest(String customerName, String customerPhone, long orderVersion) {
    }

    public record UpdateItemStatusRequest(String status, String reason, long orderVersion) {
    }

    public record UpdateOrderStatusRequest(String status, String reason, long version) {
    }

    /** {@code deliveryBoyId} null clears the assignment. */
    public record AssignDeliveryBoyRequest(UUID deliveryBoyId, long orderVersion) {
    }
}
