package com.chefpay.server.fraud;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.AuditLog;
import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.RuleConfig;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F1.5 (EOD batch): a single approver authorizes more than N cash voids in one business date.
 *
 * <p><b>Adaptation from the requirements doc:</b> ChefPay's {@code BillingService#voidPayment}
 * flow is permission-gated ({@code BILLING_MANAGE}/equivalent), not PIN-re-entry-gated per action
 * - there is no "Level-3 PIN authorizes this one void" step to count today (adding one would be a
 * real, separate change to a working, already-tested endpoint - out of scope for this round, see
 * the Round 13 report). This rule instead counts {@code PAYMENT_VOIDED} audit entries per
 * approving user for the business date, which is the real signal the requirement cares about
 * (one person voiding an unusual number of payments in a day) without requiring that flow change.
 * The requirement's second clause ("or is used on two terminals within the same minute") is NOT
 * implemented - {@code BillingService.voidPayment} never populates a {@code deviceId} on its audit
 * entry (see the Round 13 report's investigation notes), so there is no data to evaluate that
 * clause against yet.
 */
@Component
@RequiredArgsConstructor
public class ManagerPinOveruseRule implements FraudRule {

    private static final String VOID_ACTION = "PAYMENT_VOIDED";

    private final AuditLogRepository auditLogRepository;
    private final AppUserRepository appUserRepository;

    @Override
    public FraudRuleCode getCode() {
        return FraudRuleCode.MANAGER_PIN_OVERUSE;
    }

    @Override
    public boolean appliesTo(FraudRuleEventType eventType) {
        return eventType == FraudRuleEventType.EOD_BATCH;
    }

    @Override
    public List<AnomalyDraft> evaluate(FraudRuleContext context, RuleConfig config) {
        LocalDateTime start = context.businessDate().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        List<AuditLog> voidEntries = auditLogRepository.findByActionAndTimestampBetween(VOID_ACTION, start, end);

        Map<UUID, Long> countByUser = voidEntries.stream()
                .filter(e -> e.getUserId() != null)
                .collect(Collectors.groupingBy(AuditLog::getUserId, Collectors.counting()));

        long threshold = config.getThresholdValue().longValue();
        List<AnomalyDraft> drafts = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : countByUser.entrySet()) {
            if (entry.getValue() <= threshold) {
                continue;
            }
            AppUser user = appUserRepository.findById(entry.getKey()).orElse(null);
            String name = user == null ? "Unknown user" : user.getDisplayName();
            String description = name + " approved " + entry.getValue() + " cash/payment voids on "
                    + context.businessDate() + " (threshold: " + threshold + ").";
            drafts.add(new AnomalyDraft("Manager Override Overuse", description, "AppUser", entry.getKey(), null,
                    null, entry.getKey(), null));
        }
        return drafts;
    }
}
