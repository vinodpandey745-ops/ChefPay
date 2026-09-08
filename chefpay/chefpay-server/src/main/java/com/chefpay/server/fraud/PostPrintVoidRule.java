package com.chefpay.server.fraud;

import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import com.chefpay.core.domain.PaymentMethod;
import com.chefpay.core.domain.RuleConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * F1.5 "Sweethearting": bill printed -&gt; item voided/cancelled (or the bill discounted down to
 * near-nothing) -&gt; a cash payment is still recorded on the same check.
 *
 * <p><b>Adaptation from the requirements doc:</b> ChefPay has no tracked "drawer opened" event
 * independent of a payment (see the Round 13 report) - a CASH payment already implies the drawer
 * opens for change, so that leg of the original four-step chain is folded into "a CASH payment is
 * recorded" rather than checked separately. Also, this codebase's item-cancel flow uses
 * {@code CANCELLED} (see {@code OrderItemStatus}'s javadoc - {@code VOIDED} exists but nothing in
 * the current controller layer ever sets it), so this rule watches for either status.
 */
@Component
public class PostPrintVoidRule implements FraudRule {

    @Override
    public FraudRuleCode getCode() {
        return FraudRuleCode.POST_PRINT_VOID;
    }

    @Override
    public boolean appliesTo(FraudRuleEventType eventType) {
        return eventType == FraudRuleEventType.CASH_PAYMENT_RECORDED;
    }

    @Override
    public List<AnomalyDraft> evaluate(FraudRuleContext context, RuleConfig config) {
        Order order = context.order();
        if (order == null || order.getBilledAt() == null || context.payment() == null
                || context.payment().getMethod() != PaymentMethod.CASH) {
            return List.of();
        }

        LocalDateTime billedAt = order.getBilledAt();
        boolean postPrintCancellation = order.getItems().stream()
                .filter(i -> i.getStatus() == OrderItemStatus.CANCELLED || i.getStatus() == OrderItemStatus.VOIDED)
                .anyMatch(i -> i.getUpdatedAt() != null && i.getUpdatedAt().isAfter(billedAt));

        boolean nearFullDiscount = false;
        if (order.getSubtotal() != null && order.getSubtotal().compareTo(BigDecimal.ZERO) > 0
                && order.getDiscountAmount() != null) {
            BigDecimal discountPercent = order.getDiscountAmount()
                    .divide(order.getSubtotal(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            nearFullDiscount = discountPercent.compareTo(config.getThresholdValue()) >= 0;
        }

        if (!postPrintCancellation && !nearFullDiscount) {
            return List.of();
        }

        OrderItem suspectItem = order.getItems().stream()
                .filter(i -> (i.getStatus() == OrderItemStatus.CANCELLED || i.getStatus() == OrderItemStatus.VOIDED)
                        && i.getUpdatedAt() != null && i.getUpdatedAt().isAfter(billedAt))
                .findFirst().orElse(null);

        String reasonPhrase = postPrintCancellation
                ? "an item ('" + (suspectItem == null ? "unknown item" : suspectItem.getMenuItem().getName())
                + "') was cancelled after the bill was printed"
                : "the bill was discounted by " + config.getThresholdValue() + "% or more of its subtotal";
        String description = "Order " + order.getOrderNumber() + ": " + reasonPhrase
                + ", and a cash payment of " + context.payment().getAmount() + " was still recorded on this check.";

        AnomalyDraft draft = new AnomalyDraft(
                "Sweethearting",
                description,
                "Order",
                order.getId(),
                order.getId(),
                context.payment().getAmount(),
                context.payment().getReceivedBy() == null ? null : context.payment().getReceivedBy().getId(),
                context.deviceId());
        return List.of(draft);
    }
}
