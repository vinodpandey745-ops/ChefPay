package com.chefpay.server.fraud;

import com.chefpay.core.domain.AnomalySeverity;
import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.RuleConfig;
import com.chefpay.core.repository.RuleConfigRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.common.CorrelationIdHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin screen backing for the fraud/loss-prevention rule thresholds (AI Backbone Addendum F1.5 -
 * "editable from an admin screen, rather than hard-coding them"). Rows themselves are seeded once
 * by {@code DataSeeder#ensureRuleConfigDefaults} for every {@link FraudRuleCode}; this controller
 * only ever updates an existing row (never creates/deletes one - the set of rule codes is a code
 * change, not an admin action, per {@link FraudRuleCode}'s own javadoc).
 */
@RestController
@RequestMapping("/api/fraud/rule-configs")
@RequiredArgsConstructor
public class RuleConfigController {

    /** Short label for the admin table's rule column - {@link RuleConfig#getDescription()} already
     * carries the full one-sentence explanation seeded by {@code DataSeeder}, so this is purely a
     * terser display name, not a second source of truth for what the rule does. */
    private static final Map<FraudRuleCode, String> FRIENDLY_NAMES = new EnumMap<>(Map.of(
            FraudRuleCode.POST_PRINT_VOID, "Post-Print Void / Sweethearting",
            FraudRuleCode.NO_SALE_FREQUENCY, "No-Sale Frequency",
            FraudRuleCode.SPLIT_CHECK_CASH_EXTRACTION, "Cash Extraction After Payment",
            FraudRuleCode.MANAGER_PIN_OVERUSE, "Manager PIN Overuse",
            FraudRuleCode.EXCESSIVE_DISCOUNT, "Excessive Discounting",
            FraudRuleCode.AGGREGATOR_SETTLEMENT_MISMATCH, "Aggregator Settlement Mismatch",
            FraudRuleCode.PEER_BASELINE_OUTLIER, "Peer-Baseline Outlier (Statistical)"
    ));

    private final RuleConfigRepository ruleConfigRepository;
    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAuthority('RULE_CONFIG_MANAGE')")
    public ApiResponse<List<RuleConfigDtos.RuleConfigDto>> list() {
        return ApiResponse.ok(ruleConfigRepository.findAllByOrderByRuleCodeAsc().stream()
                .map(this::toDto)
                .toList());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('RULE_CONFIG_MANAGE')")
    @Transactional
    public ApiResponse<RuleConfigDtos.RuleConfigDto> update(@PathVariable UUID id,
                                                             @Valid @RequestBody RuleConfigDtos.UpdateRuleConfigRequest request,
                                                             @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        RuleConfig config = ruleConfigRepository.findById(id).orElseThrow(() -> ApiException.notFound("Rule config not found"));
        if (config.getVersion() != request.version()) {
            throw ApiException.conflict("VERSION_CONFLICT", "This rule was changed by someone else - reload and try again.");
        }

        String oldValue = summarize(config);
        if (request.thresholdValue() != null) {
            config.setThresholdValue(request.thresholdValue());
        }
        if (request.secondaryThresholdValue() != null) {
            config.setSecondaryThresholdValue(request.secondaryThresholdValue());
        }
        if (request.windowMinutes() != null) {
            config.setWindowMinutes(request.windowMinutes());
        }
        if (request.severity() != null) {
            config.setSeverity(AnomalySeverity.valueOf(request.severity()));
        }
        if (request.enabled() != null) {
            config.setEnabled(request.enabled());
        }
        RuleConfig saved = ruleConfigRepository.save(config);

        auditService.record(userId(principal), null, "RuleConfig", saved.getId(), "RULE_CONFIG_UPDATED",
                oldValue, summarize(saved), null, CorrelationIdHolder.get());
        return ApiResponse.ok(toDto(saved));
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    private String summarize(RuleConfig config) {
        return "threshold=" + config.getThresholdValue() + ", secondary=" + config.getSecondaryThresholdValue()
                + ", windowMinutes=" + config.getWindowMinutes() + ", severity=" + config.getSeverity()
                + ", enabled=" + config.isEnabled();
    }

    private RuleConfigDtos.RuleConfigDto toDto(RuleConfig config) {
        return new RuleConfigDtos.RuleConfigDto(config.getId(), config.getRuleCode().name(),
                FRIENDLY_NAMES.getOrDefault(config.getRuleCode(), config.getRuleCode().name()),
                config.getThresholdValue(), config.getSecondaryThresholdValue(), config.getWindowMinutes(),
                config.getSeverity().name(), config.isEnabled(), config.getDescription(), config.getVersion());
    }
}
