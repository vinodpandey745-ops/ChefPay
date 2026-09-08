package com.chefpay.server.fraud;

import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.Payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything a {@code FraudRule} needs to evaluate one event. Fields are nullable/optional
 * depending on {@link #eventType} - e.g. {@link #payment} is only ever set for
 * {@code CASH_PAYMENT_RECORDED}, {@link #order} is null for {@code EOD_BATCH}/
 * {@code NO_SALE_DRAWER_OPEN}. Rules that don't apply to a given event type simply return an
 * empty list rather than assuming a field is present.
 *
 * @param referenceEntityId event-specific extra id, e.g. the {@code OrderItem} id being cancelled
 *                           for {@code ITEM_CANCELLED} - null when not applicable.
 * @param eventAmount        event-specific extra amount, e.g. the cancelled item's line total for
 *                           {@code ITEM_CANCELLED} - null when not applicable.
 */
public record FraudRuleContext(
        FraudRuleEventType eventType,
        LocalDate businessDate,
        Order order,
        Payment payment,
        UUID referenceEntityId,
        BigDecimal eventAmount,
        UUID actorUserId,
        UUID deviceId
) {
}
