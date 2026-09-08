package com.chefpay.server.fraud;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a {@code FraudRule} hands back when it fires - everything except {@code severity} (which
 * {@code FraudRuleEngineService} fills in from the rule's current {@code RuleConfig} row, since
 * severity is admin-editable alongside the threshold, not something each rule hardcodes).
 */
public record AnomalyDraft(
        String category,
        String description,
        String referenceEntityType,
        UUID referenceEntityId,
        UUID referenceOrderId,
        BigDecimal amountImpact,
        UUID involvedUserId,
        UUID involvedDeviceId
) {
}
