package com.chefpay.server.orders;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.DeliveryBoy;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderMapper {

    public OrderDtos.OrderDto toDto(Order order) {
        return toDto(order, order.getItems().stream().map(this::toItemDto).toList());
    }

    /**
     * Same shape as {@link #toDto(Order)} but with an explicit item list rather than every item
     * on the order - used by the kitchen queue (Phase 3), which shows only the still-in-flight
     * lines (and only those routed to a given station, if filtering by station) rather than the
     * full historical order. Reusing {@code OrderDto}/{@code OrderItemDto} here means the JavaFX
     * kitchen display can share the exact same client-side DTOs as order-taking - one shape, one
     * source of truth, per the architecture's "no client re-implements server shapes" rule.
     */
    public OrderDtos.OrderDto toKitchenTicketDto(Order order, List<OrderItem> items) {
        return toDto(order, items.stream().map(this::toItemDto).toList());
    }

    private OrderDtos.OrderDto toDto(Order order, List<OrderDtos.OrderItemDto> itemDtos) {
        return new OrderDtos.OrderDto(
                order.getId(),
                order.getOrderNumber(),
                order.getOrderType().name(),
                order.getTable() == null ? null : order.getTable().getId(),
                order.getTable() == null ? null : order.getTable().getName(),
                order.getCustomerName(),
                order.getCustomerPhone(),
                name(order.getWaiter()),
                name(order.getCashier()),
                order.getStatus().name(),
                order.getPaymentStatus().name(),
                order.getPriority().name(),
                itemDtos,
                order.getSubtotal(),
                order.getDiscountAmount(),
                order.getTaxAmount(),
                order.getServiceChargeAmount(),
                order.getTipAmount(),
                order.getTotalAmount(),
                order.getNotes(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getVersion(),
                order.getDeliveryBoy() == null ? null : order.getDeliveryBoy().getId(),
                name(order.getDeliveryBoy())
        );
    }

    private OrderDtos.OrderItemDto toItemDto(OrderItem item) {
        return new OrderDtos.OrderItemDto(
                item.getId(),
                item.getMenuItem().getId(),
                item.getMenuItem().getName(),
                item.getQuantity(),
                item.getUnitPriceSnapshot(),
                item.lineTotal(),
                item.getStatus().name(),
                item.isPriority(),
                item.getSpecialInstructions(),
                item.getModifiersSummary(),
                item.getSentAt(),
                item.getKotNumber(),
                item.getMenuItem().isDirectSale(),
                item.getVersion()
        );
    }

    private String name(AppUser user) {
        return user == null ? null : user.getDisplayName();
    }

    private String name(DeliveryBoy deliveryBoy) {
        return deliveryBoy == null ? null : deliveryBoy.getName();
    }
}
