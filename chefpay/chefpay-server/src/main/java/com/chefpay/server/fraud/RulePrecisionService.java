package com.chefpay.server.fraud;

import com.chefpay.core.domain.Anomaly;
import com.chefpay.core.domain.AnomalyResolutionType;
import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.repository.AnomalyRepository;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Round 14 (F3.4) - "Manager Feedback Loop." Turns the labeled feedback data every resolved {@link
 * Anomaly} already carries ({@link AnomalyResolutionType} - Confirmed Theft / Legitimate Error /
 * False Positive, captured back in Round 13's EOD Manager Audit Review) into a per-rule precision
 * scorecard: how often does {@code POST_PRINT_VOID} actually turn out to be theft, versus how often
 * a manager dismisses it as a false positive? A rule with a high false-positive rate is the signal
 * a manager needs to go retune its threshold on the existing Round 13 Rule Threshold Configuration
 * screen - this report doesn't change any threshold itself, it just makes the pattern visible.
 */
@Service
@RequiredArgsConstructor
public class RulePrecisionService {

    private static final Map<FraudRuleCode, String> FRIENDLY_NAMES = new EnumMap<>(Map.of(
            FraudRuleCode.POST_PRINT_VOID, "Post-Print Void / Sweethearting",
            FraudRuleCode.NO_SALE_FREQUENCY, "No-Sale Frequency",
            FraudRuleCode.SPLIT_CHECK_CASH_EXTRACTION, "Cash Extraction After Payment",
            FraudRuleCode.MANAGER_PIN_OVERUSE, "Manager PIN Overuse",
            FraudRuleCode.EXCESSIVE_DISCOUNT, "Excessive Discounting",
            FraudRuleCode.AGGREGATOR_SETTLEMENT_MISMATCH, "Aggregator Settlement Mismatch"
    ));

    private final AnomalyRepository anomalyRepository;

    @Transactional(readOnly = true)
    public RulePrecisionDtos.RulePrecisionReportDto getReport(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.badRequest("MISSING_DATE_RANGE", "Both 'from' and 'to' dates are required.");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "'to' cannot be before 'from'.");
        }
        List<Anomaly> anomalies = anomalyRepository.findByBusinessDateBetween(from, to);

        record Tally(long total, long confirmedTheft, long legitimateError, long falsePositive, long unresolved) {
            Tally plus(Anomaly a) {
                boolean isUnresolved = a.getResolutionType() == null;
                return new Tally(total + 1,
                        confirmedTheft + (a.getResolutionType() == AnomalyResolutionType.CONFIRMED_THEFT ? 1 : 0),
                        legitimateError + (a.getResolutionType() == AnomalyResolutionType.LEGITIMATE_ERROR ? 1 : 0),
                        falsePositive + (a.getResolutionType() == AnomalyResolutionType.FALSE_POSITIVE ? 1 : 0),
                        unresolved + (isUnresolved ? 1 : 0));
            }
        }

        Map<FraudRuleCode, Tally> byRule = new EnumMap<>(FraudRuleCode.class);
        for (Anomaly anomaly : anomalies) {
            byRule.merge(anomaly.getRuleCode(), new Tally(0, 0, 0, 0, 0).plus(anomaly), (existing, fresh) ->
                    new Tally(existing.total() + fresh.total(), existing.confirmedTheft() + fresh.confirmedTheft(),
                            existing.legitimateError() + fresh.legitimateError(), existing.falsePositive() + fresh.falsePositive(),
                            existing.unresolved() + fresh.unresolved()));
        }

        List<RulePrecisionDtos.RulePrecisionRowDto> rows = byRule.entrySet().stream()
                .map(e -> {
                    Tally t = e.getValue();
                    long decided = t.total() - t.unresolved();
                    double confirmedRate = decided == 0 ? 0.0 : (t.confirmedTheft() * 100.0) / decided;
                    double falsePositiveRate = decided == 0 ? 0.0 : (t.falsePositive() * 100.0) / decided;
                    return new RulePrecisionDtos.RulePrecisionRowDto(e.getKey().name(),
                            FRIENDLY_NAMES.getOrDefault(e.getKey(), e.getKey().name()), t.total(), t.confirmedTheft(),
                            t.legitimateError(), t.falsePositive(), t.unresolved(),
                            Math.round(confirmedRate * 10) / 10.0, Math.round(falsePositiveRate * 10) / 10.0);
                })
                .sorted((a, b) -> Long.compare(b.totalAnomalies(), a.totalAnomalies()))
                .toList();

        return new RulePrecisionDtos.RulePrecisionReportDto(from, to, rows);
    }
}
