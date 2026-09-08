package com.chefpay.server.fraud;

/** Which POS action triggered {@code FraudRuleEngineService#evaluateEvent}, or the once-per-EOD
 * batch pass. A given {@code FraudRule} only looks at the event type(s) relevant to it and returns
 * empty for anything else - see each rule's javadoc for which type(s) it reacts to. */
public enum FraudRuleEventType {
    CASH_PAYMENT_RECORDED,
    ITEM_CANCELLED,
    NO_SALE_DRAWER_OPEN,
    /** Fired once per business date during EOD Review generation - covers rules that need a
     * full-day view (MANAGER_PIN_OVERUSE, EXCESSIVE_DISCOUNT) rather than a single event. */
    EOD_BATCH
}
