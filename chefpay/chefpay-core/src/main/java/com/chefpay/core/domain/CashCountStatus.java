package com.chefpay.core.domain;

/** Variance classification for a {@link CashCount} (AI Backbone Addendum F1.2). */
public enum CashCountStatus {
    MATCHED,
    ACCEPTABLE_VARIANCE,
    HIGH_VARIANCE_FLAGGED
}
