package com.chefpay.core.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Requirement §50: the server computes the total, never the client. These tests exercise that
 * math directly against the entity, independent of any HTTP/DB layer.
 */
class OrderTest {

    private OrderItem item(String qty, String price, OrderItemStatus status) {
        OrderItem item = new OrderItem();
        item.setQuantity(new BigDecimal(qty));
        item.setUnitPriceSnapshot(new BigDecimal(price));
        item.setStatus(status);
        return item;
    }

    @Test
    void subtotalSumsOnlyActiveLines() {
        Order order = new Order();
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTaxAmount(BigDecimal.ZERO);
        order.setServiceChargeAmount(BigDecimal.ZERO);
        order.setTipAmount(BigDecimal.ZERO);

        order.addItem(item("2", "250.00", OrderItemStatus.ADDED));   // 500.00
        order.addItem(item("1", "280.00", OrderItemStatus.SENT));    // 280.00
        order.addItem(item("3", "60.00", OrderItemStatus.CANCELLED)); // excluded
        order.addItem(item("1", "90.00", OrderItemStatus.VOIDED));    // excluded

        order.recalculateTotals();

        assertEquals(new BigDecimal("780.00"), order.getSubtotal());
        assertEquals(new BigDecimal("780.00"), order.getTotalAmount());
    }

    @Test
    void totalReflectsDiscountTaxServiceChargeAndTip() {
        Order order = new Order();
        order.setDiscountAmount(new BigDecimal("50.00"));
        order.setTaxAmount(new BigDecimal("36.00"));
        order.setServiceChargeAmount(new BigDecimal("18.00"));
        order.setTipAmount(new BigDecimal("20.00"));

        order.addItem(item("1", "500.00", OrderItemStatus.ADDED));
        order.recalculateTotals();

        // 500 - 50 + 36 + 18 + 20 = 524
        assertEquals(new BigDecimal("524.00"), order.getTotalAmount());
    }
}
