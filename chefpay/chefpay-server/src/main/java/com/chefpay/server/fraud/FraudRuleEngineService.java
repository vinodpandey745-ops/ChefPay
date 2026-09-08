package com.chefpay.server.fraud;

import com.chefpay.core.domain.Anomaly;
import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.EodSession;
import com.chefpay.core.domain.RuleConfig;
import com.chefpay.core.repository.AnomalyRepository;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.EodSessionRepository;
import com.chefpay.core.repository.RuleConfigRepository;
import com.chefpay.server.alerts.AlertDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the Tier-1 fraud/loss-prevention rule chain (AI Backbone Addendum F1.5). Every
 * public method here is deliberately failure-isolated at the per-rule level: a bug or unexpected
 * data shape in one {@code FraudRule} is logged and skipped, never allowed to escape and roll back
 * the caller's own transaction (recording a payment/discount/cancellation must never fail because
 * the fraud engine had a problem - same "never break the underlying action" discipline
 * {@code NotificationService} already follows for its own side-effect calls).
 *
 * <p>Call sites: {@code BillingService.recordPayment} (CASH branch), {@code BillingService
 * .voidPayment}-adjacent item-cancel path in {@code OrderService}, the new "No Sale" endpoint in
 * {@code BillingController}, and {@code EodService}'s Review-step generation (EOD_BATCH).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudRuleEngineService {

    private final List<FraudRule> rules;
    private final RuleConfigRepository ruleConfigRepository;
    private final AnomalyRepository anomalyRepository;
    private final EodSessionRepository eodSessionRepository;
    private final AppUserRepository appUserRepository;
    private final CctvLinkService cctvLinkService;
    private final AlertDispatchService alertDispatchService;

    @Transactional
    public void evaluateEvent(FraudRuleContext context) {
        for (FraudRule rule : rules) {
            if (!rule.appliesTo(context.eventType())) {
                continue;
            }
            try {
                evaluateOneRule(rule, context);
            } catch (Exception ex) {
                log.error("Fraud rule {} failed to evaluate event {} - skipping this rule for this event.",
                        rule.getCode(), context.eventType(), ex);
            }
        }
    }

    /** Called once per business date during EOD Review generation - runs every rule that declares
     * interest in {@code EOD_BATCH} (currently MANAGER_PIN_OVERUSE, EXCESSIVE_DISCOUNT). */
    @Transactional
    public void runEodBatch(java.time.LocalDate businessDate) {
        FraudRuleContext context = new FraudRuleContext(FraudRuleEventType.EOD_BATCH, businessDate, null, null,
                null, null, null, null);
        evaluateEvent(context);
    }

    private void evaluateOneRule(FraudRule rule, FraudRuleContext context) {
        Optional<RuleConfig> configOpt = ruleConfigRepository.findByRuleCode(rule.getCode());
        if (configOpt.isEmpty() || !configOpt.get().isEnabled()) {
            return;
        }
        RuleConfig config = configOpt.get();
        List<AnomalyDraft> drafts = rule.evaluate(context, config);
        for (AnomalyDraft draft : drafts) {
            persistAnomaly(rule, config, context, draft);
        }
    }

    private void persistAnomaly(FraudRule rule, RuleConfig config, FraudRuleContext context, AnomalyDraft draft) {
        EodSession session = eodSessionRepository.findByBusinessDate(context.businessDate()).orElse(null);
        // getReferenceById avoids a full fetch purely to attach an FK we already know the id of -
        // Hibernate resolves the proxy lazily, and it's never dereferenced here, only assigned.
        AppUser involvedUser = draft.involvedUserId() == null ? null
                : appUserRepository.getReferenceById(draft.involvedUserId());

        LocalDateTime detectedAt = LocalDateTime.now();
        Anomaly anomaly = Anomaly.builder()
                .businessDate(context.businessDate())
                .eodSession(session)
                .ruleCode(rule.getCode())
                .severity(config.getSeverity())
                .category(draft.category())
                .description(draft.description())
                .referenceEntityType(draft.referenceEntityType())
                .referenceEntityId(draft.referenceEntityId())
                .referenceOrderId(draft.referenceOrderId())
                .amountImpact(draft.amountImpact())
                .involvedUser(involvedUser)
                .involvedDeviceId(draft.involvedDeviceId())
                .detectedAt(detectedAt)
                // Round 14 F4.4: best-effort footage link, never blocks anomaly creation if no
                // CctvProvider is installed or the lookup fails - see CctvLinkService's javadoc.
                .cctvFootageUrl(cctvLinkService.tryLinkFootage(detectedAt))
                .build();
        Anomaly saved = anomalyRepository.save(anomaly);

        // Round 14 F4.2: immediate multi-channel alert for this newly-created anomaly. Defensive
        // try/catch matches every other post-persist side effect in this codebase (e.g.
        // NotificationService#create's own internal catch-all) - a notification failure must never
        // roll back or fail the anomaly detection that triggered it.
        try {
            alertDispatchService.dispatchForAnomaly(saved, false);
        } catch (Exception ex) {
            log.error("Critical alert dispatch failed for anomaly {} - anomaly was still recorded.", saved.getId(), ex);
        }
    }
}
