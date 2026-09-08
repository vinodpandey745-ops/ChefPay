package com.chefpay.server.fraud;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public final class RuleConfigDtos {

    private RuleConfigDtos() {
    }

    public record RuleConfigDto(UUID id, String ruleCode, String friendlyName, BigDecimal thresholdValue,
                                 BigDecimal secondaryThresholdValue, Integer windowMinutes, String severity,
                                 boolean enabled, String description, long version) {
    }

    /** All fields optional/nullable-on-the-wire except {@code version} - a PATCH-style update where
     * the admin screen only ever changes one or two fields at a time; whichever fields are null are
     * left as-is rather than blanked out (see {@code RuleConfigController#update}). */
    public record UpdateRuleConfigRequest(BigDecimal thresholdValue, BigDecimal secondaryThresholdValue,
                                           Integer windowMinutes, String severity, Boolean enabled,
                                           @NotNull Long version) {
    }
}
