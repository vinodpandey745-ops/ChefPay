package com.chefpay.server.subscription.payment;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Subscription;
import com.chefpay.core.domain.SubscriptionPayment;
import com.chefpay.core.domain.SubscriptionPaymentPurpose;
import com.chefpay.core.domain.SubscriptionPaymentStatus;
import com.chefpay.core.domain.SubscriptionPlan;
import com.chefpay.core.domain.SubscriptionStatus;
import com.chefpay.core.repository.SubscriptionPaymentRepository;
import com.chefpay.core.repository.SubscriptionPlanRepository;
import com.chefpay.core.repository.SubscriptionRepository;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Subscription Renewal and Plan Upgrade requirement: owns every Razorpay-driven mutation of a
 * {@link Subscription} plus its paired {@link SubscriptionPayment} audit row, so {@code
 * SubscriptionController}'s interactive {@code /renew/verify} endpoint and {@link
 * RazorpayWebhookController}'s durability backstop apply the EXACT SAME activation logic rather
 * than two copies that could drift apart - see {@link #activate} javadoc.
 *
 * <p><b>Reactivation/plan-change math is mirrored faithfully from {@code
 * PlatformOwnerController#reactivateSubscription}</b> (that endpoint's own javadoc explains why
 * both {@code status} and {@code expiryDate} must move together in one write: {@code
 * EntitlementService#refreshStatus} recomputes status from {@code expiryDate} on every read, so
 * setting {@code status=ACTIVE} alone on an already-expired subscription would not durably stick).
 * That admin-triggered endpoint is untouched by this class - this is a separate, payment-driven
 * path for the restaurant's own subscriber-facing renewal flow.
 *
 * <p><b>The single most important guarantee in this whole feature:</b> {@link Subscription} is
 * mutated ONLY inside {@link #activate}, which is reached only when a {@link SubscriptionPayment}
 * row that is still {@code CREATED} is confirmed genuine (a verified Razorpay signature, or an
 * already-verified webhook payload) - see {@link #verifyAndActivate}/{@link #activateFromWebhook}.
 * A failed or cancelled payment never calls {@link #activate} and therefore never touches the
 * {@link Subscription} row at all.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionRenewalService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;
    private final RazorpayClient razorpayClient;

    @Value("${chefpay.razorpay.key-id:}")
    private String keyId;

    @Value("${chefpay.razorpay.key-secret:}")
    private String keySecret;

    @Value("${chefpay.razorpay.webhook-secret:}")
    private String webhookSecret;

    /** What {@code POST /api/subscription/renew/initiate} hands back to the caller: everything the
     * frontend's Razorpay Checkout widget needs, plus the PUBLIC {@code key-id} (never the secret -
     * see {@link RazorpayClient}'s own javadoc for why this one value alone is safe to expose). */
    public record InitiateResult(SubscriptionPayment payment, String razorpayOrderId, long amountPaise,
                                  String razorpayKeyId) {
    }

    @Transactional
    public InitiateResult initiate(UUID branchId, SubscriptionPaymentPurpose purpose, UUID targetPlanId,
                                    AppUser initiatedBy) {
        Subscription subscription = subscriptionRepository.findByBranchId(branchId)
                .orElseThrow(() -> ApiException.notFound(
                        "No subscription configured for this branch yet. Contact Bistrodesk support."));

        SubscriptionPlan targetPlan;
        if (purpose == SubscriptionPaymentPurpose.REACTIVATE) {
            targetPlan = subscription.getPlan();
        } else {
            if (targetPlanId == null) {
                throw ApiException.badRequest("VALIDATION_ERROR", "targetPlanId is required to change plans.");
            }
            targetPlan = subscriptionPlanRepository.findById(targetPlanId)
                    .orElseThrow(() -> ApiException.notFound("Plan not found"));
            if (!targetPlan.isActive()) {
                throw ApiException.badRequest("PLAN_INACTIVE", "This plan is no longer available for new subscriptions.");
            }
        }

        SubscriptionPayment payment = SubscriptionPayment.builder()
                .branch(subscription.getBranch())
                .plan(targetPlan)
                .purpose(purpose)
                .amount(targetPlan.getPrice())
                .currency("INR")
                .status(SubscriptionPaymentStatus.CREATED)
                // Placeholder until the Razorpay order below is created - a real order id always
                // replaces this before the row is actually usable; never left as-is on success.
                .gatewayOrderId("PENDING")
                .initiatedBy(initiatedBy)
                .build();
        payment = subscriptionPaymentRepository.save(payment);

        RazorpayClient.OrderResult order;
        try {
            order = razorpayClient.createOrder(keyId, keySecret, targetPlan.getPrice(), "INR", payment.getId().toString());
        } catch (RazorpayException ex) {
            payment.setStatus(SubscriptionPaymentStatus.FAILED);
            payment.setFailureReason(ex.getMessage());
            subscriptionPaymentRepository.save(payment);
            throw gatewayError(ex);
        }
        payment.setGatewayOrderId(order.orderId());
        payment = subscriptionPaymentRepository.save(payment);

        return new InitiateResult(payment, order.orderId(), order.amountPaise(), keyId);
    }

    /** {@code POST /api/subscription/renew/verify}: the interactive path, called by the frontend
     * immediately after Razorpay Checkout's own {@code handler} callback fires. Rejects a payment
     * row that isn't still {@code CREATED} (already verified/failed/cancelled - never re-processed)
     * and a {@code razorpayOrderId} that doesn't match the row it was created for (a caller could
     * otherwise try to mark an unrelated payment row as paid using a genuine signature for a
     * DIFFERENT order) before ever calling Razorpay. */
    @Transactional
    public Subscription verifyAndActivate(UUID subscriptionPaymentId, String razorpayOrderId,
                                           String razorpayPaymentId, String razorpaySignature) {
        SubscriptionPayment payment = subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .orElseThrow(() -> ApiException.notFound("Payment record not found"));
        if (payment.getStatus() != SubscriptionPaymentStatus.CREATED) {
            throw ApiException.conflict("PAYMENT_ALREADY_PROCESSED", "This payment has already been processed.");
        }
        if (razorpayOrderId == null || !razorpayOrderId.equals(payment.getGatewayOrderId())) {
            throw ApiException.badRequest("ORDER_MISMATCH", "This payment does not match the order it was created for.");
        }

        boolean verified;
        try {
            verified = razorpayClient.verifyPaymentSignature(keySecret, razorpayOrderId, razorpayPaymentId, razorpaySignature);
        } catch (RazorpayException ex) {
            throw gatewayError(ex);
        }

        if (!verified) {
            payment.setStatus(SubscriptionPaymentStatus.FAILED);
            payment.setFailureReason("Payment signature verification failed.");
            subscriptionPaymentRepository.save(payment);
            throw ApiException.badRequest("PAYMENT_VERIFICATION_FAILED",
                    "Payment verification failed. Your subscription has not been changed.");
        }

        payment.setGatewayPaymentId(razorpayPaymentId);
        return activate(payment);
    }

    /** {@code POST /api/subscription/renew/cancel}: the frontend's Razorpay Checkout {@code
     * modal.ondismiss} callback calls this when the payer closes the widget without paying - marks
     * the row {@code CANCELLED} so it doesn't linger forever as an ambiguous {@code CREATED} row.
     * Idempotent and safe to call on a row that has already resolved another way (e.g. a webhook
     * beat the dismiss callback to activating it) - never downgrades an already-{@code SUCCESS}
     * row, and a row already {@code FAILED}/{@code CANCELLED} is simply left as-is. */
    @Transactional
    public void cancel(UUID subscriptionPaymentId) {
        SubscriptionPayment payment = subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .orElseThrow(() -> ApiException.notFound("Payment record not found"));
        if (payment.getStatus() == SubscriptionPaymentStatus.CREATED) {
            payment.setStatus(SubscriptionPaymentStatus.CANCELLED);
            subscriptionPaymentRepository.save(payment);
        }
    }

    /** {@code RazorpayWebhookController}'s durability backstop for a {@code payment.captured}
     * event: activates the matching {@code CREATED} payment exactly the way {@link
     * #verifyAndActivate} does, but keyed by Razorpay's own order id (a webhook payload has no
     * {@code SubscriptionPayment} id of its own). A no-op (not an error) when no matching row is
     * {@code CREATED} - either this order id is unknown here, or {@code /verify} (or an earlier
     * webhook delivery) already activated it, and Razorpay's own webhook delivery already retries
     * on a non-2xx response, so "nothing left to do" must return success. */
    @Transactional
    public void activateFromWebhook(String razorpayOrderId, String razorpayPaymentId) {
        subscriptionPaymentRepository.findByGatewayOrderId(razorpayOrderId).ifPresent(payment -> {
            if (payment.getStatus() == SubscriptionPaymentStatus.CREATED) {
                payment.setGatewayPaymentId(razorpayPaymentId);
                activate(payment);
            }
        });
    }

    public List<SubscriptionPayment> recentPayments(UUID branchId) {
        return subscriptionPaymentRepository.findByBranchIdOrderByCreatedAtDesc(branchId);
    }

    public String webhookSecret() {
        return webhookSecret;
    }

    /** The one place a successful payment actually changes the {@link Subscription} - see this
     * class's own javadoc for why both {@code /verify} and the webhook backstop route through
     * here rather than each reimplementing the reactivate/plan-change math. Mirrors {@code
     * PlatformOwnerController#reactivateSubscription} exactly: {@code expiryDate} moves to "today +
     * the plan being activated's durationDays" (the CURRENT plan for a reactivation, the NEW target
     * plan for a plan change - both already resolved onto {@link SubscriptionPayment#getPlan()} at
     * {@link #initiate} time) and {@code status} moves to {@code ACTIVE}, together, in the same
     * transaction. For {@link SubscriptionPaymentPurpose#PLAN_CHANGE} the subscription's plan is
     * also swapped to the payment's plan. */
    private Subscription activate(SubscriptionPayment payment) {
        Subscription subscription = subscriptionRepository.findByBranchId(payment.getBranch().getId())
                .orElseThrow(() -> ApiException.notFound("No subscription found for this branch."));

        LocalDate today = LocalDate.now();
        LocalDate previousExpiry = subscription.getExpiryDate();
        LocalDate newExpiry = today.plusDays(payment.getPlan().getDurationDays());

        if (payment.getPurpose() == SubscriptionPaymentPurpose.PLAN_CHANGE) {
            subscription.setPlan(payment.getPlan());
        }
        subscription.setExpiryDate(newExpiry);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(subscription);

        payment.setPreviousExpiryDate(previousExpiry);
        payment.setNewExpiryDate(newExpiry);
        payment.setStatus(SubscriptionPaymentStatus.SUCCESS);
        subscriptionPaymentRepository.save(payment);

        return subscription;
    }

    private ApiException gatewayError(RazorpayException ex) {
        return new ApiException("PAYMENT_GATEWAY_ERROR", ex.getMessage(), HttpStatus.BAD_GATEWAY);
    }
}
