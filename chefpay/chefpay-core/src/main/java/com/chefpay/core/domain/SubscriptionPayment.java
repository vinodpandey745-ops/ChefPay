package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Subscription Renewal and Plan Upgrade requirement ("A payment/transaction record should be
 * maintained for each renewal or plan change"): one row per attempted renewal/plan-change payment
 * against Razorpay, from order creation through verification (or failure/cancellation). Every
 * write to {@link Subscription#getExpiryDate()}/{@link Subscription#getStatus()}/{@link
 * Subscription#getPlan()} driven by this payment feature happens ONLY when this row transitions
 * {@code CREATED -> SUCCESS} (see {@code SubscriptionController#verifyRenewal} and {@code
 * RazorpayWebhookController}, which share one activation method) - a {@code FAILED}/{@code
 * CANCELLED} row never touches the {@link Subscription} at all, per the requirement's "If payment
 * fails or is cancelled, the subscription status should remain unchanged."
 *
 * <p><b>GST decision (documented once, applied everywhere - backend amount calculation and the
 * chefpay-web renewal screen alike):</b> {@link #amount} is the plan's {@link
 * SubscriptionPlan#getPrice()} ONLY - GST is NOT added into the amount actually charged via
 * Razorpay for this release. The existing {@link SubscriptionPlan#getGstPercent()} field is kept
 * purely informational on the plan-picker display (as it already was on this screen before this
 * feature existed), matching how {@link SubscriptionController} already surfaces plan price and
 * GST percent as two separate numbers rather than one combined figure. A future release wiring up
 * real GST-inclusive invoicing can build on {@link #amount} without a breaking change here.
 */
@Entity
@Table(name = "subscription_payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class SubscriptionPayment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /** For {@link SubscriptionPaymentPurpose#REACTIVATE} this is the branch's CURRENT plan at the
     * time the payment was initiated; for {@link SubscriptionPaymentPurpose#PLAN_CHANGE} this is
     * the NEW target plan the caller selected - see {@link #purpose}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionPaymentPurpose purpose;

    /** The exact amount charged via Razorpay - see this class's javadoc for the GST decision. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Column(nullable = false, length = 8)
    private String currency = "INR";

    /** Best-effort only, and frequently left {@code null}: Razorpay Checkout's client-side {@code
     * handler} callback (and the {@code payment.captured} webhook payload) report a {@code
     * razorpay_payment_id}/{@code payment.entity.id} but not which tender the payer actually used
     * unless a separate "fetch payment by id" call is made against Razorpay's API - not done here,
     * since nothing in this feature's requirement depends on knowing the exact method after the
     * fact (the requirement only asks that Card and UPI both be OFFERED, which Razorpay Checkout's
     * own widget already handles). Reuses the existing {@link PaymentMethod} enum (CARD/UPI/...)
     * rather than inventing a payment-gateway-specific one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionPaymentStatus status;

    @Column(name = "gateway_order_id", nullable = false, length = 100)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 100)
    private String gatewayPaymentId;

    /** Who kicked off this renewal/plan-change from the restaurant's own UI - nullable since the
     * webhook backstop path ({@code RazorpayWebhookController}) has no authenticated caller of its
     * own and may be the one to flip this row to {@code SUCCESS} instead of the interactive
     * {@code /verify} call. Bistrodesk fix (StackOverflowError precedent, see {@link
     * Device#getLastUser()}'s javadoc): defense-in-depth exclusion even though {@link AppUser}
     * holds no collection back to this entity, so no real cycle exists today. */
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiated_by_id")
    private AppUser initiatedBy;

    /** Audit trail of exactly what this payment changed on the {@link Subscription} - both null
     * until this row reaches {@code SUCCESS}. */
    @Column(name = "previous_expiry_date")
    private LocalDate previousExpiryDate;

    @Column(name = "new_expiry_date")
    private LocalDate newExpiryDate;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;
}
