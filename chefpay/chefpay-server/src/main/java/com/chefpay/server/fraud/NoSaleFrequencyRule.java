package com.chefpay.server.fraud;

import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.RuleConfig;
import com.chefpay.core.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F1.5: more than N no-sale drawer opens by one cashier within a rolling window (default 60
 * minutes). Backed by the new "No Sale" action ({@code BillingController}'s
 * {@code POST /api/billing/no-sale}) added this round specifically so this rule has a real signal
 * to count - ChefPay had no "drawer opened without a sale" concept at all before Round 13 (see the
 * Round 13 report).
 */
@Component
@RequiredArgsConstructor
public class NoSaleFrequencyRule implements FraudRule {

    public static final String NO_SALE_AUDIT_ACTION = "NO_SALE_DRAWER_OPEN";

    private final AuditLogRepository auditLogRepository;

    @Override
    public FraudRuleCode getCode() {
        return FraudRuleCode.NO_SALE_FREQUENCY;
    }

    @Override
    public boolean appliesTo(FraudRuleEventType eventType) {
        return eventType == FraudRuleEventType.NO_SALE_DRAWER_OPEN;
    }

    @Override
    public List<AnomalyDraft> evaluate(FraudRuleContext context, RuleConfig config) {
        if (context.actorUserId() == null) {
            return List.of();
        }
        int windowMinutes = config.getWindowMinutes() == null ? 60 : config.getWindowMinutes();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes(windowMinutes);

        long count = auditLogRepository
                .findByUserIdAndActionAndTimestampBetween(context.actorUserId(), NO_SALE_AUDIT_ACTION, windowStart, now)
                .size();

        // Fire exactly at the crossing point (count first exceeds the threshold), not on every
        // subsequent no-sale in the same window - otherwise the 5th, 6th, 7th... no-sale in one
        // window would each spawn a fresh duplicate Anomaly instead of one flag for the pattern.
        if (count != config.getThresholdValue().longValue() + 1) {
            return List.of();
        }

        String description = "This cashier opened the cash drawer without a sale " + count
                + " times in the last " + windowMinutes + " minutes (threshold: "
                + config.getThresholdValue().longValue() + ").";
        return List.of(new AnomalyDraft("No-Sale Frequency", description, "AppUser", context.actorUserId(), null,
                null, context.actorUserId(), context.deviceId()));
    }
}
