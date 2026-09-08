package com.chefpay.server.subscription;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Feature;
import com.chefpay.core.domain.Subscription;
import com.chefpay.core.domain.SubscriptionPayment;
import com.chefpay.core.domain.SubscriptionPaymentPurpose;
import com.chefpay.core.domain.SubscriptionPlan;
import com.chefpay.core.repository.SubscriptionPlanRepository;
import com.chefpay.core.repository.SubscriptionRepository;
import com.chefpay.core.service.EntitlementService;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.payment.SubscriptionRenewalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 2 (items 25-26): read-only subscription/plan/entitlement info for the restaurant's own
 * Manager/Admin UI. Every write to a {@link Subscription} or {@link SubscriptionPlan} happens only
 * through {@code PlatformOwnerController} - see that controller's javadoc and Section F of
 * PHASE2_ORG_SUBSCRIPTION_DESIGN.md for why this asymmetry is deliberate (the platform owner, not
 * the restaurant, controls licensing).
 *
 * <p>Bistrodesk Phase 1: {@link Subscription} moved from per-restaurant to per-branch (see that
 * entity's javadoc), so every read here now resolves "the caller's branch" via {@link
 * #resolveBranchId} rather than "the one restaurant." An explicit {@code branchId} query param is
 * accepted (and access-checked via {@link BranchAccessService}) for a client that already knows
 * which branch it means; when omitted, this falls back to the caller's configured default branch,
 * then to their sole accessible branch, then (for the common single-branch install, where a user
 * typically has no branch assignment configured at all) to the restaurant's one and only Branch -
 * so an existing single-branch install's frontend needs zero changes to keep working exactly as
 * before. Full branch-aware UI wiring (a branch switcher actually driving this param) is Phase 4/5's
 * job, not this phase's.
 */
@RestController
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final EntitlementService entitlementService;
    private final BranchAccessService branchAccessService;
    /** Subscription Renewal and Plan Upgrade requirement: the Razorpay-driven renew/plan-change
     * flow below is deliberately the ONE deviation from this class's own "read-only, every write
     * happens through PlatformOwnerController" javadoc - see {@link SubscriptionRenewalService}'s
     * own class javadoc for why this is safe (activation only ever follows a verified payment). */
    private final SubscriptionRenewalService subscriptionRenewalService;

    /** Item 25-26: current plan/status/remaining-days for this branch. {@code SUBSCRIPTION_VIEW}
     * visibility differs by role (see DataSeeder's ROLE_PERMISSIONS - Owner/Admin/Manager hold it,
     * Cashier/Waiter/Kitchen do not), matching the design's "visible per role-permission matrix,
     * not hardcoded to Admin-only" requirement. */
    @GetMapping("/api/subscription")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    public ApiResponse<SubscriptionDtos.SubscriptionDto> get(@RequestParam(required = false) UUID branchId,
                                                              @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Subscription subscription = currentSubscription(branchId, principal);
        entitlementService.refreshStatus(subscription);
        return ApiResponse.ok(toDto(subscription));
    }

    @GetMapping("/api/subscription/plans")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    public ApiResponse<List<SubscriptionDtos.PlanDto>> plans() {
        List<SubscriptionDtos.PlanDto> dtos = subscriptionPlanRepository.findByActiveTrueOrderByDisplayOrderAsc()
                .stream().map(this::toPlanDto).toList();
        return ApiResponse.ok(dtos);
    }

    /** Item 24: every enabled feature code at once, for the client's locked/unlocked UI. Any
     * authenticated user (not gated on {@code SUBSCRIPTION_VIEW}) since a Cashier/Waiter still needs
     * to know which features are unlocked for THEM even though they can't see the billing/renewal
     * details behind {@code SUBSCRIPTION_VIEW}. */
    @GetMapping("/api/entitlements")
    public ApiResponse<SubscriptionDtos.EntitlementsResponse> entitlements(@RequestParam(required = false) UUID branchId,
                                                                            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Subscription subscription = currentSubscription(branchId, principal);
        return ApiResponse.ok(new SubscriptionDtos.EntitlementsResponse(
                entitlementService.enabledFeatureCodes(subscription).stream().sorted().toList()));
    }

    // ---- Subscription Renewal and Plan Upgrade requirement: payment-driven renewal/plan-change.
    // See SubscriptionRenewalService's own javadoc for the activation guarantee; this controller's
    // job is only request validation, branch resolution (identical to the read endpoints above),
    // and mapping the service's results onto the same SubscriptionDto/PlanDto shapes the rest of
    // this controller already returns. ----

    /** Step 1 of the flow ("Reactivate Current Plan" or "Choose Another Plan"): creates a {@code
     * SubscriptionPayment} row and a matching Razorpay order, returning everything the frontend's
     * Razorpay Checkout widget needs to open the payment sheet. Reachable even when the
     * subscription is EXPIRED/GRACE_PERIOD - {@code SUBSCRIPTION_VIEW} is a role-permission gate,
     * entirely independent of the subscription's own live status (see {@code
     * EntitlementService#blocksAllFeatures}, which only ever affects {@code @RequiresFeature}
     * gates, never a plain {@code @PreAuthorize} permission check like this one) - precisely the
     * moment someone needs to reach this endpoint. */
    @PostMapping("/api/subscription/renew/initiate")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    public ApiResponse<SubscriptionPaymentDtos.InitiateRenewalResponse> initiateRenewal(
            @RequestBody SubscriptionPaymentDtos.InitiateRenewalRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        UUID branchId = resolveBranchId(request.branchId(), principal);
        SubscriptionPaymentPurpose purpose = parsePurpose(request.purpose());
        AppUser initiatedBy = branchAccessService.resolve(principal);
        SubscriptionRenewalService.InitiateResult result =
                subscriptionRenewalService.initiate(branchId, purpose, request.targetPlanId(), initiatedBy);
        SubscriptionPayment payment = result.payment();
        String description = purpose == SubscriptionPaymentPurpose.REACTIVATE
                ? "Reactivate " + payment.getPlan().getName()
                : "Switch to " + payment.getPlan().getName();
        return ApiResponse.ok(new SubscriptionPaymentDtos.InitiateRenewalResponse(
                payment.getId(), result.razorpayOrderId(), result.razorpayKeyId(), result.amountPaise(),
                payment.getCurrency(), payment.getPlan().getName(), description));
    }

    /** Step 2: called by the frontend immediately after Razorpay Checkout's own success callback
     * fires. The subscription is mutated ONLY if the signature genuinely verifies - see {@link
     * SubscriptionRenewalService#verifyAndActivate}'s javadoc; a failed verification throws (4xx)
     * and leaves the subscription completely untouched. */
    @PostMapping("/api/subscription/renew/verify")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    public ApiResponse<SubscriptionDtos.SubscriptionDto> verifyRenewal(
            @RequestBody SubscriptionPaymentDtos.VerifyRenewalRequest request) {
        Subscription subscription = subscriptionRenewalService.verifyAndActivate(
                request.subscriptionPaymentId(), request.razorpayOrderId(),
                request.razorpayPaymentId(), request.razorpaySignature());
        return ApiResponse.ok(toDto(subscription));
    }

    /** Step 2 (alternate ending): the frontend's Razorpay Checkout {@code modal.ondismiss}
     * callback calls this when the payer closes the widget without paying, so a {@code CREATED}
     * row doesn't linger forever as ambiguous. Never touches the subscription. */
    @PostMapping("/api/subscription/renew/cancel")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    public ApiResponse<Void> cancelRenewal(@RequestBody SubscriptionPaymentDtos.CancelRenewalRequest request) {
        subscriptionRenewalService.cancel(request.subscriptionPaymentId());
        return ApiResponse.ok(null);
    }

    /** "Recent Payments" section on the Subscription screen - newest first. */
    @GetMapping("/api/subscription/payments")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    public ApiResponse<List<SubscriptionPaymentDtos.SubscriptionPaymentDto>> payments(
            @RequestParam(required = false) UUID branchId, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        UUID resolvedBranchId = resolveBranchId(branchId, principal);
        List<SubscriptionPaymentDtos.SubscriptionPaymentDto> dtos = subscriptionRenewalService.recentPayments(resolvedBranchId)
                .stream().map(this::toPaymentDto).toList();
        return ApiResponse.ok(dtos);
    }

    private SubscriptionPaymentPurpose parsePurpose(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "purpose is required (REACTIVATE or PLAN_CHANGE).");
        }
        try {
            return SubscriptionPaymentPurpose.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("VALIDATION_ERROR", "purpose must be REACTIVATE or PLAN_CHANGE.");
        }
    }

    private SubscriptionPaymentDtos.SubscriptionPaymentDto toPaymentDto(SubscriptionPayment p) {
        return new SubscriptionPaymentDtos.SubscriptionPaymentDto(p.getId(), p.getPlan().getName(),
                p.getPurpose().name(), p.getAmount(), p.getCurrency(), p.getStatus().name(),
                p.getPaymentMethod() == null ? null : p.getPaymentMethod().name(),
                p.getPreviousExpiryDate(), p.getNewExpiryDate(), p.getFailureReason(), p.getCreatedAt());
    }

    private Subscription currentSubscription(UUID requestedBranchId, AuthenticatedPrincipal principal) {
        UUID branchId = resolveBranchId(requestedBranchId, principal);
        return subscriptionRepository.findByBranchId(branchId)
                .orElseThrow(() -> ApiException.notFound(
                        "No subscription configured for this branch yet. Contact Bistrodesk support."));
    }

    /** See this class's own javadoc for the fallback order - now a thin call-through to {@link
     * BranchAccessService#resolveEffectiveBranchId}, which Bistrodesk Phase 4 promoted this exact
     * chain to (shared with {@code RequiresFeatureAspect}'s entitlement gate) rather than keeping a
     * second copy here. */
    private UUID resolveBranchId(UUID requestedBranchId, AuthenticatedPrincipal principal) {
        return branchAccessService.resolveEffectiveBranchId(principal, requestedBranchId);
    }

    private SubscriptionDtos.SubscriptionDto toDto(Subscription s) {
        return new SubscriptionDtos.SubscriptionDto(s.getId(),
                s.getBranch() == null ? null : s.getBranch().getId(),
                s.getBranch() == null ? null : s.getBranch().getName(),
                s.getPlan().getName(), s.getPlan().getId(),
                s.getStatus().name(), s.getStartDate(), s.getExpiryDate(),
                entitlementService.remainingDays(s), s.getGracePeriodDays(), s.getWarningThresholdsDays(),
                s.getSupportPhone(), s.getVersion());
    }

    private SubscriptionDtos.PlanDto toPlanDto(SubscriptionPlan p) {
        return new SubscriptionDtos.PlanDto(p.getId(), p.getName(), p.getDescription(), p.getDurationDays(),
                p.getPrice(), p.getGstPercent(), p.getMaxBranches(), p.getMaxTerminals(), p.getMaxUsers(),
                p.isTrial(), p.isActive(), p.getDisplayOrder(),
                p.getFeatures().stream().map(Feature::getCode).sorted().toList());
    }
}
