package com.chefpay.core.domain;

/** Review state for an {@link Anomaly} (AI Backbone Addendum F1.6 - EOD Manager Audit Review). */
public enum AnomalyStatus {
    UNREVIEWED,
    RESOLVED,
    ESCALATED
}
