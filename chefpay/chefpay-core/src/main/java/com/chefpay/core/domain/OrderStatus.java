package com.chefpay.core.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Full order lifecycle state machine (requirement §13). The backend is the only place that may
 * advance an order's status - see {@code canTransitionTo}, enforced server-side on every write
 * (§63: "never allow arbitrary status changes from UI"). CANCELLED is a permission-gated side
 * branch reachable from any pre-billing state; once a bill exists (BILLED and later) a "cancel"
 * is really a void/refund, which is Phase 4 scope, so cancellation is deliberately not offered
 * past BILL_REQUESTED here.
 */
public enum OrderStatus {
    DRAFT,
    PLACED,
    SENT_TO_KITCHEN,
    ACCEPTED,
    PREPARING,
    READY,
    SERVED,
    BILL_REQUESTED,
    BILLED,
    PAYMENT_PENDING,
    PAID,
    CLOSED,
    CANCELLED;

    private static final Set<OrderStatus> CANCELLABLE_FROM =
            EnumSet.of(DRAFT, PLACED, SENT_TO_KITCHEN, ACCEPTED, PREPARING, READY, SERVED, BILL_REQUESTED);

    public boolean canTransitionTo(OrderStatus next) {
        if (this == next) {
            return true;
        }
        if (next == CANCELLED) {
            return CANCELLABLE_FROM.contains(this);
        }
        return switch (this) {
            case DRAFT -> next == PLACED;
            case PLACED -> next == SENT_TO_KITCHEN;
            case SENT_TO_KITCHEN -> next == ACCEPTED;
            case ACCEPTED -> next == PREPARING;
            case PREPARING -> next == READY;
            case READY -> next == SERVED;
            case SERVED -> next == BILL_REQUESTED;
            case BILL_REQUESTED -> next == BILLED;
            case BILLED -> next == PAYMENT_PENDING;
            case PAYMENT_PENDING -> next == PAID;
            case PAID -> next == CLOSED;
            case CLOSED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED;
    }

    public boolean isOpen() {
        return !isTerminal();
    }
}
