package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Client-side mirror of {@code com.chefpay.server.fraud.RuleConfigDtos}. */
public final class RuleConfigDtos {

    private RuleConfigDtos() {
    }

    public record RuleConfigDto(UUID id, String ruleCode, String friendlyName, BigDecimal thresholdValue,
                                 BigDecimal secondaryThresholdValue, Integer windowMinutes, String severity,
                                 boolean enabled, String description, long version) {
    }

    public record UpdateRuleConfigRequest(BigDecimal thresholdValue, BigDecimal secondaryThresholdValue,
                                           Integer windowMinutes, String severity, Boolean enabled, Long version) {
    }
}
