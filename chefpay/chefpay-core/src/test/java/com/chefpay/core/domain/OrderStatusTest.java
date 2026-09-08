package com.chefpay.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requirement §13/§63: the backend must never allow an arbitrary status jump. */
class OrderStatusTest {

    @Test
    void happyPathFollowsTheFullLifecycleInOrder() {
        OrderStatus[] path = {
                OrderStatus.DRAFT, OrderStatus.PLACED, OrderStatus.SENT_TO_KITCHEN, OrderStatus.ACCEPTED,
                OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.SERVED, OrderStatus.BILL_REQUESTED,
                OrderStatus.BILLED, OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, OrderStatus.CLOSED
        };
        for (int i = 0; i < path.length - 1; i++) {
            final int idx = i; // capture an effectively-final copy for the lambda below
            assertTrue(path[idx].canTransitionTo(path[idx + 1]),
                    () -> path[idx] + " should be able to move to " + path[idx + 1]);
        }
    }

    @Test
    void cannotSkipStepsForward() {
        assertFalse(OrderStatus.PLACED.canTransitionTo(OrderStatus.PREPARING));
        assertFalse(OrderStatus.DRAFT.canTransitionTo(OrderStatus.PAID));
    }

    @Test
    void cannotMoveBackward() {
        assertFalse(OrderStatus.PREPARING.canTransitionTo(OrderStatus.PLACED));
        assertFalse(OrderStatus.PAID.canTransitionTo(OrderStatus.SERVED));
    }

    @Test
    void cancelIsAllowedBeforeBillingButNotAfter() {
        assertTrue(OrderStatus.PLACED.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.PREPARING.canTransitionTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.BILLED.canTransitionTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED));
    }

    @Test
    void terminalStatesAcceptNoFurtherTransitions() {
        for (OrderStatus target : OrderStatus.values()) {
            if (target == OrderStatus.CLOSED) {
                continue; // self-transition is a documented no-op, not a real move
            }
            assertFalse(OrderStatus.CLOSED.canTransitionTo(target), () -> "CLOSED should not move to " + target);
        }
        for (OrderStatus target : OrderStatus.values()) {
            if (target == OrderStatus.CANCELLED) {
                continue;
            }
            assertFalse(OrderStatus.CANCELLED.canTransitionTo(target), () -> "CANCELLED should not move to " + target);
        }
        assertTrue(OrderStatus.CLOSED.isTerminal());
        assertTrue(OrderStatus.CANCELLED.isTerminal());
        assertFalse(OrderStatus.PAID.isTerminal());
    }
}
