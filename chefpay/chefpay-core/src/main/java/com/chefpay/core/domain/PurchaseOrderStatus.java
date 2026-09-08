package com.chefpay.core.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Full Purchase Order lifecycle (Round 12 §12-§26). Mirrors {@link OrderStatus}'s pattern of a
 * single forward-moving state machine with an explicit "is this move legal from here" check kept
 * on the enum itself, rather than scattered across the service - the same reasoning
 * {@code OrderStatus#canTransitionTo}'s javadoc gives.
 *
 * <p>DRAFT is a PO still being assembled (items being added/edited, not yet submitted).
 * PENDING_APPROVAL/APPROVED/REJECTED are the role-based approval fork (see
 * {@code PurchaseOrderService#submitForApproval} - whether a creator's submission lands directly
 * in APPROVED or first passes through PENDING_APPROVAL depends on whether they hold
 * {@code PURCHASE_ORDER_APPROVE} and whether {@code Restaurant#poApprovalRequired} is even on).
 * SENT_TO_SUPPLIER records that the PO document was actually shared (print/email/WhatsApp/future
 * API - see {@link PurchaseOrderShareLog}). PARTIALLY_RECEIVED/RECEIVED track the receiving
 * workflow (§22-§24); CLOSED is the terminal "fully settled, nothing more to do" state a manager
 * marks explicitly once RECEIVED (mirrors leaving a manual "are we done with this" checkpoint
 * rather than auto-closing the moment every line reaches its ordered quantity, since a manager may
 * still want to reconcile invoices first). CANCELLED is reachable from any pre-receiving state.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    SENT_TO_SUPPLIER,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED;

    private static final Set<PurchaseOrderStatus> CANCELLABLE_FROM =
            EnumSet.of(DRAFT, PENDING_APPROVAL, APPROVED, SENT_TO_SUPPLIER, PARTIALLY_RECEIVED);

    public boolean canTransitionTo(PurchaseOrderStatus next) {
        if (this == next) {
            return true;
        }
        if (next == CANCELLED) {
            return CANCELLABLE_FROM.contains(this);
        }
        return switch (this) {
            case DRAFT -> next == PENDING_APPROVAL || next == APPROVED;
            case PENDING_APPROVAL -> next == APPROVED || next == REJECTED;
            case APPROVED -> next == SENT_TO_SUPPLIER || next == PARTIALLY_RECEIVED || next == RECEIVED;
            case SENT_TO_SUPPLIER -> next == PARTIALLY_RECEIVED || next == RECEIVED;
            case PARTIALLY_RECEIVED -> next == PARTIALLY_RECEIVED || next == RECEIVED;
            case RECEIVED -> next == CLOSED;
            case REJECTED, CLOSED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED || this == REJECTED;
    }
}
