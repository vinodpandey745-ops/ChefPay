package com.chefpay.server.fraud;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.RuleConfig;
import com.chefpay.core.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F1.5 (EOD batch): a cashier's manual discounts for the business date exceed a configurable % of
 * their gross sales for that date. "Gross sales" is each attributed order's pre-discount
 * {@code subtotal}; "their" orders are attributed by {@code Order#cashier} (the user who billed/
 * closed the order) - {@code Order#waiter} is a separate field for whoever took the order and
 * isn't what applied the discount, so this rule deliberately doesn't group by it.
 */
@Component
@RequiredArgsConstructor
public class ExcessiveDiscountRule implements FraudRule {

    private final OrderRepository orderRepository;

    @Override
    public FraudRuleCode getCode() {
        return FraudRuleCode.EXCESSIVE_DISCOUNT;
    }

    @Override
    public boolean appliesTo(FraudRuleEventType eventType) {
        return eventType == FraudRuleEventType.EOD_BATCH;
    }

    @Override
    public List<AnomalyDraft> evaluate(FraudRuleContext context, RuleConfig config) {
        LocalDateTime start = context.businessDate().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        List<Order> orders = orderRepository.findByCreatedAtBetween(start, end);

        Map<UUID, BigDecimal> grossByCashier = new HashMap<>();
        Map<UUID, BigDecimal> discountByCashier = new HashMap<>();
        Map<UUID, AppUser> cashierById = new HashMap<>();

        for (Order order : orders) {
            AppUser cashier = order.getCashier();
            if (cashier == null) {
                continue;
            }
            cashierById.putIfAbsent(cashier.getId(), cashier);
            grossByCashier.merge(cashier.getId(), nullToZero(order.getSubtotal()), BigDecimal::add);
            discountByCashier.merge(cashier.getId(), nullToZero(order.getDiscountAmount()), BigDecimal::add);
        }

        List<AnomalyDraft> drafts = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : discountByCashier.entrySet()) {
            BigDecimal discountTotal = entry.getValue();
            BigDecimal grossTotal = grossByCashier.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (grossTotal.compareTo(BigDecimal.ZERO) <= 0 || discountTotal.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal percent = discountTotal.divide(grossTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            if (percent.compareTo(config.getThresholdValue()) <= 0) {
                continue;
            }
            AppUser cashier = cashierById.get(entry.getKey());
            String description = (cashier == null ? "This cashier" : cashier.getDisplayName())
                    + " applied discounts totalling " + discountTotal + " against gross sales of " + grossTotal
                    + " on " + context.businessDate() + " (" + percent.setScale(1, RoundingMode.HALF_UP)
                    + "%, threshold: " + config.getThresholdValue() + "%).";
            drafts.add(new AnomalyDraft("Excessive Discounting", description, "AppUser", entry.getKey(), null,
                    discountTotal, entry.getKey(), null));
        }
        return drafts;
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
