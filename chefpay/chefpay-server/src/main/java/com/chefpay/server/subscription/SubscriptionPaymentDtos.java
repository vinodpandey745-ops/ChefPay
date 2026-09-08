package com.chefpay.server.subscription;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Subscription Renewal and Plan Upgrade requirement: request/response shapes for the
 * Razorpay-driven renew/plan-change flow on {@link SubscriptionController}. Kept in a dedicated
 * file (rather than folded into {@link SubscriptionDtos}) since these are writable-flow shapes,
 * unlike every existing record in that file, which is explicitly documented as read-only. */
public final class SubscriptionPaymentDtos {

    private SubscriptionPaymentDtos() {
    }

    /** {@code branchId} optional - resolved the same way every other endpoint on this controller
     * does (see {@code SubscriptionController#resolveBranchId}). {@code purpose} is {@code
     * "REACTIVATE"} or {@code "PLAN_CHANGE"}; {@code targetPlanId} is required only for {@code
     * PLAN_CHANGE}. */
    public record InitiateRenewalRequest(UUID branchId, String purpose, UUID targetPlanId) {
    }

    /** Everything the frontend's Razorpay Checkout widget needs to open the payment sheet.
     * {@code razorpayKeyId} is Razorpay's PUBLIC key - safe to expose to the browser; the secret
     * key is never sent to any client. */
    public record InitiateRenewalResponse(UUID subscriptionPaymentId, String razorpayOrderId,
                                           String razorpayKeyId, long amountPaise, String currency,
                                           String planName, String description) {
    }

    public record VerifyRenewalRequest(UUID subscriptionPaymentId, String razorpayOrderId,
                                        String razorpayPaymentId, String razorpaySignature) {
    }

    public record CancelRenewalRequest(UUID subscriptionPaymentId) {
    }

    /** One row of the "Recent Payments" list. {@code paymentMethod} is frequently {@code null} -
     * see {@code SubscriptionPayment#getPaymentMethod()}'s javadoc for why. */
    public record SubscriptionPaymentDto(UUID id, String planName, String purpose, BigDecimal amount,
                                          String currency, String status, String paymentMethod,
                                          LocalDate previousExpiryDate, LocalDate newExpiryDate,
                                          String failureReason, LocalDateTime createdAt) {
    }
}
