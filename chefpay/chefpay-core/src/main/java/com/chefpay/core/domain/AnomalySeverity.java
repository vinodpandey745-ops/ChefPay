package com.chefpay.core.domain;

/** Severity ordering for {@link Anomaly}/{@link RuleConfig} - matches the AI Backbone Addendum's
 * "Critical/High first" sort and its red/amber/neutral color coding (Section 11.2). Declared in
 * this order so {@code Enum#compareTo}/{@code Comparator.naturalOrder()} sorts ascending
 * LOW..CRITICAL - callers that want severity-descending (worst first) reverse it explicitly. */
public enum AnomalySeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
