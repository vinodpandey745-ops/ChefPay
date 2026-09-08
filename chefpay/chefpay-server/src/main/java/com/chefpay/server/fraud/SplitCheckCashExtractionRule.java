package com.chefpay.server.fraud;

import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.PaymentMethod;
import com.chefpay.core.domain.RuleConfig;
import com.chefpay.core.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F1.5: a cash-paid check has an item cancelled/voided AFTER payment was already recorded.
 *
 * <p><b>Adaptation from the requirements doc:</b> the reference pattern ("a cash-paid multi-item
 * check is split into separate checks after payment, followed by a partial cancellation") assumes
 * a check-splitting flow ChefPay doesn't have in this exact shape - {@code BillingService
 * #splitBillEvenly} computes even shares for display/collection purposes, it doesn't fork one
 * {@code Order} into multiple persisted checks. The underlying loss-prevention signal (cash
 * already collected, then the paid-for goods are cancelled - an "extraction" of value after the
 * drawer already balanced) is still real and still detectable without a literal split-check
 * feature, so this rule watches for that narrower, always-buildable condition instead.
 */
@Component
@RequiredArgsConstructor
public class SplitCheckCashExtractionRule implements FraudRule {

    private final PaymentRepository paymentRepository;

    @Override
    public FraudRuleCode getCode() {
        return FraudRuleCode.SPLIT_CHECK_CASH_EXTRACTION;
    }

    @Override
    public boolean appliesTo(FraudRuleEventType eventType) {
        return eventType == FraudRuleEventType.ITEM_CANCELLED;
    }

    @Override
    public List<AnomalyDraft> evaluate(FraudRuleContext context, RuleConfig config) {
        Order order = context.order();
        if (order == null) {
            return List.of();
        }
        boolean hasCashPayment = paymentRepository.findByOrderIdOrderByReceivedAtAsc(order.getId()).stream()
                .anyMatch(p -> !p.isVoided() && p.getMethod() == PaymentMethod.CASH);
        if (!hasCashPayment) {
            return List.of();
        }

        String description = "Order " + order.getOrderNumber()
                + ": an item was cancelled after a cash payment was already recorded on this check.";
        return List.of(new AnomalyDraft("Post-Payment Cancellation", description, "OrderItem",
                context.referenceEntityId(), order.getId(), context.eventAmount(), context.actorUserId(),
                context.deviceId()));
    }
}
