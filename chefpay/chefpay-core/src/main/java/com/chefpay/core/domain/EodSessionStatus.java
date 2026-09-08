package com.chefpay.core.domain;

/**
 * Wizard step/state for {@link EodSession} (AI Backbone Addendum F1.1) - "Ingest -> Blind Cash
 * Count -> Fraud & Audit Review -> Finalize & GL Sync". Deliberately a single forward-moving
 * machine, same pattern as {@link OrderStatus}/{@link PurchaseOrderStatus} - the wizard's current
 * step IS this session's persisted status, so resuming after a crash/network loss (F1.1's
 * "EOD must be resumable") is just: re-fetch the session and render whichever step its status
 * says, no separate "resume token" concept needed.
 */
public enum EodSessionStatus {
    INGESTING,
    CASH_COUNT,
    REVIEW,
    FINALIZED,
    /** Terminal escape hatch - a session started in error, or businessDate needs redoing. Not
     * reachable from FINALIZED (a finalized day is closed for good, matching F1.3/NFR-5's
     * never-hard-delete stance on finalized records). */
    CANCELLED;

    public boolean canTransitionTo(EodSessionStatus next) {
        if (this == next) {
            return true;
        }
        if (next == CANCELLED) {
            return this == INGESTING || this == CASH_COUNT || this == REVIEW;
        }
        return switch (this) {
            case INGESTING -> next == CASH_COUNT;
            case CASH_COUNT -> next == REVIEW;
            case REVIEW -> next == FINALIZED;
            case FINALIZED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return this == FINALIZED || this == CANCELLED;
    }
}
