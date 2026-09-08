package com.chefpay.core.domain;

/**
 * Tier-1 deterministic fraud/loss-prevention rules (AI Backbone Addendum F1.5) plus the F1.1
 * aggregator-mismatch check, which reuses the same {@link RuleConfig}/{@link Anomaly} machinery
 * even though it runs during EOD channel ingestion rather than as a POS event/batch rule. A plain
 * enum (not a DB-driven catalog) because each code names a specific {@code FraudRule} bean in
 * chefpay-server - adding a new one is still a code change (a new rule class), only its
 * *thresholds* are meant to be edited without a release (see {@link RuleConfig}).
 */
public enum FraudRuleCode {
    /** Bill printed -&gt; item voided/cancelled or effectively 100% discounted -&gt; a cash payment is
     * still recorded on the same check ("sweethearting"). */
    POST_PRINT_VOID,
    /** More than N no-sale drawer opens by one cashier within a rolling window. */
    NO_SALE_FREQUENCY,
    /** A cash-paid order has an item cancelled/voided after payment was already recorded. */
    SPLIT_CHECK_CASH_EXTRACTION,
    /** A Level-3 (override-capable) PIN authorizes more than N cash voids in one business date, or
     * is used on two different terminals within the same minute. */
    MANAGER_PIN_OVERUSE,
    /** A cashier's manual discounts for the business date exceed a configurable % of their gross
     * sales for that date. */
    EXCESSIVE_DISCOUNT,
    /** F1.1's aggregator settlement vs POS-recorded value mismatch, evaluated during EOD ingestion. */
    AGGREGATOR_SETTLEMENT_MISMATCH,
    /** F3.3: a cashier's void-to-sales ratio, cash-vs-card ratio, or discount % for a business date
     * deviates more than N standard deviations from their same-role peer group's trailing-30-day
     * average - a statistical enhancement layer on top of the deterministic rules above, not a
     * replacement for them. */
    PEER_BASELINE_OUTLIER
}
