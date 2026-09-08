package com.chefpay.plugin.api;

import com.chefpay.plugin.api.dto.PaymentRequestDto;
import com.chefpay.plugin.api.dto.PaymentResultDto;

/**
 * Extension point for card/UPI/wallet payment processing through an external
 * gateway or POS terminal. v1 requirements are explicit that payments are
 * manual/offline (cash, UPI, card - no integration) - this interface exists
 * so a real gateway (Razorpay, PayU, a card-machine SDK, ...) can be dropped
 * in later without touching the billing screen's core flow. When no
 * PaymentGatewayProvider is enabled, the billing screen just records the
 * chosen payment method manually, exactly as v1 requires.
 */
public interface PaymentGatewayProvider extends ChefPayPlugin {

    PaymentResultDto charge(PaymentRequestDto request);

    default PaymentResultDto refund(String transactionReference, PaymentRequestDto original) {
        return new PaymentResultDto(false, null, "Refunds not supported by " + getName());
    }
}
