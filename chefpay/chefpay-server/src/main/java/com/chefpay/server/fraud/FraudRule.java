package com.chefpay.server.fraud;

import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.RuleConfig;

import java.util.List;

/**
 * One independently-testable Tier-1 fraud/loss-prevention rule (AI Backbone Addendum Section 2.2 -
 * "a small Strategy-pattern rule chain in Spring... this avoids pulling in a heavyweight rules
 * engine while keeping each rule independently testable"). Each implementation is a plain
 * {@code @Component} - {@code FraudRuleEngineService} discovers all of them via Spring's normal
 * {@code List<FraudRule>} bean-collection injection, no registry/factory needed.
 *
 * <p>A rule receives its own current {@link RuleConfig} row (thresholds, window, enabled flag -
 * never hardcoded, per Section 2.2's "single highest-leverage change") and returns every anomaly
 * it finds for the given {@link FraudRuleContext} - usually zero or one, but batch rules
 * (MANAGER_PIN_OVERUSE, EXCESSIVE_DISCOUNT) may return one per offending employee.
 */
public interface FraudRule {

    FraudRuleCode getCode();

    /** Which event type(s) this rule reacts to - {@code FraudRuleEngineService} only calls a rule
     * for event types it declares interest in, so most rules never even see irrelevant events. */
    boolean appliesTo(FraudRuleEventType eventType);

    List<AnomalyDraft> evaluate(FraudRuleContext context, RuleConfig config);
}
