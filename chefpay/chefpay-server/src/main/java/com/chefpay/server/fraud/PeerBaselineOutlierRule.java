package com.chefpay.server.fraud;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.Payment;
import com.chefpay.core.domain.PaymentMethod;
import com.chefpay.core.domain.RuleConfig;
import com.chefpay.core.repository.OrderRepository;
import com.chefpay.core.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * F3.3 (EOD batch) - "Peer-Baseline Anomaly Scoring (ML-Assisted, Optional)". An enhancement layer
 * on top of the deterministic Tier-1 rules (F1.5), not a replacement for them, exactly as the spec
 * frames it. For every cashier active on the EOD batch's business date, this computes three
 * per-cashier-per-date features already implied by existing Tier-1 data - void-to-sales ratio,
 * cash-vs-card ratio, and discount % - and flags that cashier's day as an outlier when any feature
 * is more than {@code config.getThresholdValue()} standard deviations (default 2.00, per the
 * spec's own "&gt;2 standard deviations" example) from the mean of their peer group: every
 * {@code AppUser} sharing the same {@code Role}, over the trailing 30 days (spec: "same role, same
 * shift period, trailing 30 days"). Entirely deterministic SQL/Java statistics - no ML library -
 * per the spec's own "computable directly in SQL/Java without a dedicated ML library". If ChefPay
 * later adopts the optional Python ML microservice, this is the natural seam to swap in an
 * Isolation Forest score without changing anything downstream of {@link AnomalyDraft}.
 *
 * <p><b>Adaptations from the requirements doc (documented, not oversights):</b>
 * <ul>
 * <li>ChefPay has no {@code Shift} entity (same limitation {@code ExcessiveDiscountRule} already
 * works around) - the practical peer-group/feature unit here is "one {@code AppUser}, one business
 * date", not a true shift.</li>
 * <li>"Average drawer-open duration" (one of the spec's four suggested features) is omitted -
 * ChefPay has no tracked drawer-open event independent of a payment, the same adaptation already
 * documented on {@code PostPrintVoidRule} and {@code NoSaleFrequencyRule}.</li>
 * <li>"Void" is read as {@link Payment#isVoided()} (a corrected/voided tender, attributed to
 * {@link Payment#getReceivedBy()}) rather than an {@code OrderItem} cancellation - voiding a
 * payment is the one existing per-cashier "void" action this codebase's data model already
 * tracks ({@code BillingService.voidPayment}).</li>
 * <li>If a role's peer population over the trailing 30 days has fewer than {@link
 * #MIN_PEER_SAMPLE} (cashier, date) rows, this rule silently skips that role for this run rather
 * than flagging against a statistically meaningless sample - the spec's own Section 12.1
 * assumption ("~90 days of transaction history") already acknowledges real history is needed
 * before this kind of scoring means anything.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PeerBaselineOutlierRule implements FraudRule {

    private static final int TRAILING_DAYS = 30;
    private static final int MIN_PEER_SAMPLE = 5;

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    @Override
    public FraudRuleCode getCode() {
        return FraudRuleCode.PEER_BASELINE_OUTLIER;
    }

    @Override
    public boolean appliesTo(FraudRuleEventType eventType) {
        return eventType == FraudRuleEventType.EOD_BATCH;
    }

    @Override
    public List<AnomalyDraft> evaluate(FraudRuleContext context, RuleConfig config) {
        LocalDate businessDate = context.businessDate();
        LocalDateTime windowStart = businessDate.minusDays(TRAILING_DAYS - 1L).atStartOfDay();
        LocalDateTime windowEnd = businessDate.plusDays(1).atStartOfDay();

        Map<CashierDateKey, FeatureBuilder> builders = new HashMap<>();
        Map<UUID, AppUser> cashierById = new HashMap<>();

        for (Order order : orderRepository.findByCreatedAtBetween(windowStart, windowEnd)) {
            AppUser cashier = order.getCashier();
            if (cashier == null || cashier.getRole() == null) {
                continue;
            }
            cashierById.putIfAbsent(cashier.getId(), cashier);
            CashierDateKey key = new CashierDateKey(cashier.getId(), order.getCreatedAt().toLocalDate());
            FeatureBuilder builder = builders.computeIfAbsent(key, k -> new FeatureBuilder());
            builder.gross = builder.gross.add(nullToZero(order.getSubtotal()));
            builder.discount = builder.discount.add(nullToZero(order.getDiscountAmount()));
        }

        for (Payment payment : paymentRepository.findByReceivedAtBetween(windowStart, windowEnd)) {
            AppUser cashier = payment.getReceivedBy();
            if (cashier == null || cashier.getRole() == null) {
                continue;
            }
            cashierById.putIfAbsent(cashier.getId(), cashier);
            CashierDateKey key = new CashierDateKey(cashier.getId(), payment.getReceivedAt().toLocalDate());
            FeatureBuilder builder = builders.computeIfAbsent(key, k -> new FeatureBuilder());
            builder.totalPayments++;
            if (payment.isVoided()) {
                builder.voidedPayments++;
            }
            if (payment.getMethod() == PaymentMethod.CASH) {
                builder.cashPayments++;
            }
        }

        // Every (cashier, date) row's computed features, grouped by role so each row can be
        // compared against every OTHER row sharing that role over the trailing window.
        Map<UUID, List<Features>> featuresByRole = new HashMap<>();
        Map<CashierDateKey, Features> featuresByKey = new HashMap<>();
        for (Map.Entry<CashierDateKey, FeatureBuilder> entry : builders.entrySet()) {
            AppUser cashier = cashierById.get(entry.getKey().cashierId());
            if (cashier == null) {
                continue;
            }
            Features features = entry.getValue().build(entry.getKey(), cashier.getRole().getId());
            featuresByKey.put(entry.getKey(), features);
            featuresByRole.computeIfAbsent(features.roleId(), k -> new ArrayList<>()).add(features);
        }

        List<AnomalyDraft> drafts = new ArrayList<>();
        for (Map.Entry<CashierDateKey, Features> entry : featuresByKey.entrySet()) {
            if (!entry.getKey().businessDate().equals(businessDate)) {
                continue; // only flag TODAY - history is peer context here, never re-flagged on a later run
            }
            Features todays = entry.getValue();
            List<Features> peers = featuresByRole.get(todays.roleId());
            if (peers == null || peers.size() < MIN_PEER_SAMPLE) {
                continue;
            }
            AppUser cashier = cashierById.get(entry.getKey().cashierId());
            checkFeature(drafts, cashier, todays, peers, config, "void-to-sales ratio", Features::voidRatio);
            checkFeature(drafts, cashier, todays, peers, config, "cash-vs-card ratio", Features::cashRatio);
            checkFeature(drafts, cashier, todays, peers, config, "discount percentage", Features::discountPercent);
        }
        return drafts;
    }

    private void checkFeature(List<AnomalyDraft> drafts, AppUser cashier, Features todays, List<Features> peers,
                               RuleConfig config, String featureName, ToDoubleFunction<Features> extractor) {
        double[] values = peers.stream().mapToDouble(extractor).toArray();
        double mean = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        double stddev = Math.sqrt(variance);
        if (stddev <= 0.0001) {
            return; // no variation across peers - a z-score here would be meaningless/infinite
        }
        double value = extractor.applyAsDouble(todays);
        double threshold = config.getThresholdValue() == null ? 2.0 : config.getThresholdValue().doubleValue();
        double z = (value - mean) / stddev;
        if (Math.abs(z) <= threshold) {
            return;
        }
        String description = (cashier == null ? "This cashier" : cashier.getDisplayName()) + "'s " + featureName
                + " on " + todays.businessDate() + " was " + round(value) + " - " + round(Math.abs(z))
                + " standard deviations " + (z > 0 ? "above" : "below") + " the " + peers.size()
                + "-sample peer average of " + round(mean) + " for their role over the trailing "
                + TRAILING_DAYS + " days (threshold: " + round(threshold) + " SD).";
        drafts.add(new AnomalyDraft("Peer-Baseline Outlier", description, "AppUser",
                todays.cashierId(), null, null, todays.cashierId(), null));
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record CashierDateKey(UUID cashierId, LocalDate businessDate) {
    }

    private record Features(UUID cashierId, LocalDate businessDate, UUID roleId, double voidRatio, double cashRatio,
                             double discountPercent) {
    }

    /** Mutable per-(cashier,date) accumulator, built up across two separate repository scans
     * (orders for gross/discount, payments for void/cash-vs-card) before being frozen into an
     * immutable {@link Features} row. */
    private static final class FeatureBuilder {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        int totalPayments = 0;
        int voidedPayments = 0;
        int cashPayments = 0;

        Features build(CashierDateKey key, UUID roleId) {
            double voidRatio = totalPayments == 0 ? 0.0 : (double) voidedPayments / (double) totalPayments;
            double cashRatio = totalPayments == 0 ? 0.0 : (double) cashPayments / (double) totalPayments;
            double discountPercent = gross.compareTo(BigDecimal.ZERO) <= 0 ? 0.0
                    : discount.divide(gross, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
            return new Features(key.cashierId(), key.businessDate(), roleId, voidRatio, cashRatio, discountPercent);
        }
    }
}
