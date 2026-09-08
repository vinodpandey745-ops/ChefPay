package com.chefpay.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure state-machine tests for the table status guard (no Spring context / DB needed) - this is
 * exactly the kind of unit test requirement §71 asks for on the entities/services that carry
 * business rules.
 */
class RestaurantTableTest {

    private RestaurantTable table(TableStatus status) {
        RestaurantTable t = new RestaurantTable();
        t.setStatus(status);
        return t;
    }

    @Test
    void availableTableCanBeReservedOrOccupied() {
        RestaurantTable t = table(TableStatus.AVAILABLE);
        assertTrue(t.canTransitionTo(TableStatus.RESERVED));
        assertTrue(t.canTransitionTo(TableStatus.OCCUPIED));
        assertTrue(t.canTransitionTo(TableStatus.ORDER_PLACED));
    }

    @Test
    void availableTableCannotJumpToBillRequested() {
        RestaurantTable t = table(TableStatus.AVAILABLE);
        assertFalse(t.canTransitionTo(TableStatus.BILL_REQUESTED));
        assertFalse(t.canTransitionTo(TableStatus.PAYMENT_PENDING));
    }

    @Test
    void occupiedTableCanGoStraightToAvailableOncePaid() {
        // Regression test for a real bug found during live testing: this used to assertFalse here,
        // requiring an order to reach status CLOSED before its table could free up - but nothing in
        // the order lifecycle ever actually sets order status to CLOSED after PAID (PAID is already
        // the terminal "guest is done" state), so that old rule meant a table a guest had just fully
        // paid at could never turn green again (see BillingService#recordPayment's syncTableStatus
        // call, and RestaurantTable#canTransitionTo's javadoc). Only RESERVED still requires passing
        // back through AVAILABLE first.
        RestaurantTable t = table(TableStatus.OCCUPIED);
        assertTrue(t.canTransitionTo(TableStatus.AVAILABLE));
        assertTrue(t.canTransitionTo(TableStatus.PREPARING));
        assertTrue(t.canTransitionTo(TableStatus.CLOSED));
        assertFalse(t.canTransitionTo(TableStatus.RESERVED));
    }

    @Test
    void closedTableReturnsToAvailableOnly() {
        RestaurantTable t = table(TableStatus.CLOSED);
        assertTrue(t.canTransitionTo(TableStatus.AVAILABLE));
        assertFalse(t.canTransitionTo(TableStatus.OCCUPIED));
    }

    @Test
    void blockedTableCanOnlyBeUnblocked() {
        RestaurantTable t = table(TableStatus.BLOCKED);
        assertTrue(t.canTransitionTo(TableStatus.AVAILABLE));
        assertFalse(t.canTransitionTo(TableStatus.RESERVED));
    }
}
