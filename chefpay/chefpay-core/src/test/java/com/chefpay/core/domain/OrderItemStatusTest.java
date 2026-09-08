package com.chefpay.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requirement §14: each order line has independent kitchen-facing state. */
class OrderItemStatusTest {

    @Test
    void happyPathFollowsKitchenProgression() {
        assertTrue(OrderItemStatus.ADDED.canTransitionTo(OrderItemStatus.SENT));
        assertTrue(OrderItemStatus.SENT.canTransitionTo(OrderItemStatus.ACCEPTED));
        assertTrue(OrderItemStatus.ACCEPTED.canTransitionTo(OrderItemStatus.PREPARING));
        assertTrue(OrderItemStatus.PREPARING.canTransitionTo(OrderItemStatus.READY));
        assertTrue(OrderItemStatus.READY.canTransitionTo(OrderItemStatus.SERVED));
    }

    @Test
    void notYetSentItemCanBePlainlyRemoved() {
        assertTrue(OrderItemStatus.ADDED.canTransitionTo(OrderItemStatus.CANCELLED));
    }

    @Test
    void sentItemMustGoThroughCancelRequestedNotStraightToCancelled() {
        assertTrue(OrderItemStatus.PREPARING.canTransitionTo(OrderItemStatus.CANCEL_REQUESTED));
        assertFalse(OrderItemStatus.PREPARING.canTransitionTo(OrderItemStatus.CANCELLED));
        assertTrue(OrderItemStatus.CANCEL_REQUESTED.canTransitionTo(OrderItemStatus.CANCELLED));
    }

    @Test
    void servedItemsCannotBeVoidedOrCancelled() {
        assertFalse(OrderItemStatus.SERVED.canTransitionTo(OrderItemStatus.VOIDED));
        assertFalse(OrderItemStatus.SERVED.canTransitionTo(OrderItemStatus.CANCELLED));
        assertTrue(OrderItemStatus.SERVED.isTerminal());
    }

    @Test
    void cannotSkipFromAddedStraightToReady() {
        assertFalse(OrderItemStatus.ADDED.canTransitionTo(OrderItemStatus.READY));
    }
}
