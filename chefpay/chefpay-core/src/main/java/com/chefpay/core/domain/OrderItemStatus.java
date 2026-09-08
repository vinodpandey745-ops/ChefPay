package com.chefpay.core.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Independent per-line-item state (requirement §14) - one order can have Biryani READY while
 * Ice Cream is still ADDED. CANCEL_REQUESTED is the "item already sent to kitchen, someone wants
 * to pull it" path - it requires the same permission gate as an order-level cancel once past
 * ADDED (enforced in the orders service, not here). VOIDED is a hard write-off after the fact
 * (e.g. kitchen ran out mid-prep), always permission-gated.
 */
public enum OrderItemStatus {
    ADDED,
    SENT,
    ACCEPTED,
    PREPARING,
    READY,
    SERVED,
    CANCEL_REQUESTED,
    CANCELLED,
    VOIDED;

    private static final Set<OrderItemStatus> CAN_REQUEST_CANCEL_FROM =
            EnumSet.of(SENT, ACCEPTED, PREPARING, READY);

    public boolean canTransitionTo(OrderItemStatus next) {
        if (this == next) {
            return true;
        }
        if (next == CANCELLED && this == ADDED) {
            return true; // not yet sent - a plain remove, no kitchen involvement
        }
        if (next == CANCEL_REQUESTED) {
            return CAN_REQUEST_CANCEL_FROM.contains(this);
        }
        if (next == CANCELLED && this == CANCEL_REQUESTED) {
            return true;
        }
        if (next == VOIDED) {
            return this != SERVED && this != CANCELLED && this != VOIDED;
        }
        return switch (this) {
            case ADDED -> next == SENT;
            case SENT -> next == ACCEPTED;
            case ACCEPTED -> next == PREPARING;
            case PREPARING -> next == READY;
            case READY -> next == SERVED;
            case SERVED, CANCELLED, VOIDED, CANCEL_REQUESTED -> false;
        };
    }

    public boolean isTerminal() {
        return this == SERVED || this == CANCELLED || this == VOIDED;
    }
}
