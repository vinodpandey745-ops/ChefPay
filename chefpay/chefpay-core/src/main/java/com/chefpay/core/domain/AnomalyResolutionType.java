package com.chefpay.core.domain;

/** How a manager ultimately classified a resolved {@link Anomaly} (AI Backbone Addendum F1.5 -
 * "Confirmed Theft / Legitimate Error / False Positive"). This is exactly the labeled feedback
 * data F3.4's rule-precision report and any future ML layer would train on - not built this round
 * (Phase 3 scope), but the label is captured now so nothing is lost in the meantime. */
public enum AnomalyResolutionType {
    LEGITIMATE_ERROR,
    CONFIRMED_THEFT,
    FALSE_POSITIVE
}
