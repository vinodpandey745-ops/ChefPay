package com.chefpay.server.subscription;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Subscription;
import com.chefpay.core.repository.SubscriptionRepository;
import com.chefpay.core.service.EntitlementService;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Bistrodesk Phase 4 (requirement #24's confirmed enforcement gap - see {@link RequiresFeature}'s
 * javadoc for the full "why"): the actual server-side gate behind every {@link RequiresFeature}
 * annotation. Runs before the controller method body, so an unentitled branch is refused with a
 * clear 403 before any read or write happens - the same guarantee {@code @PreAuthorize} already
 * gives for permissions, now extended to subscription/plan entitlement.
 *
 * <p><b>Which branch does this check?</b> The annotated method's parameters are NOT inspected
 * (reflecting into an arbitrary request-body DTO for a {@code branchId} field is fragile and
 * differs per endpoint - one more way to silently check the wrong thing, a worse outcome than a
 * simple, uniform rule). Instead this resolves the SAME "effective branch" every read-only
 * subscription endpoint already resolves via {@link BranchAccessService#resolveEffectiveBranchId}:
 * an explicit {@code ?branchId=} query parameter (if the request has one) wins; otherwise the
 * caller's own default/sole accessible branch, or (single-branch install) the install's one Branch.
 * For the large majority of installs and endpoints (one branch, or a branch-scoped staff member
 * acting within their own branch) this is exactly the branch the action is actually for. A
 * multi-branch, unrestricted caller invoking a write whose target branch is only expressed inside
 * the request body (not a query param) is the one case this resolves against the CALLER's own
 * context rather than the request's specific target - a deliberate, documented trade-off, not an
 * oversight: {@link BranchAccessService} still separately verifies (inside each service's own
 * logic) that the caller may touch whatever branch the body actually names, so this gate's only job
 * - "has this branch paid for this capability" - never mis-fires against the wrong branch's plan
 * for the common case, and fails closed (a clear {@code BRANCH_REQUIRED} error) rather than
 * guessing when it can't determine a single branch at all.
 *
 * <p>Method-level {@link RequiresFeature} wins over a class-level one on the same method (a
 * narrower, more specific annotation should never be widened by a broader one) - never both
 * checked together, since a method carrying its own annotation is deliberately overriding, not
 * adding to, whatever the controller declares.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class RequiresFeatureAspect {

    private final BranchAccessService branchAccessService;
    private final SubscriptionRepository subscriptionRepository;
    private final EntitlementService entitlementService;

    @Around("@within(com.chefpay.server.subscription.RequiresFeature) || @annotation(com.chefpay.server.subscription.RequiresFeature)")
    public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequiresFeature annotation = AnnotatedElementUtils.findMergedAnnotation(method, RequiresFeature.class);
        if (annotation == null) {
            // Class-level only, resolved via the target class rather than the (possibly interface/
            // proxy) method handle above.
            annotation = AnnotatedElementUtils.findMergedAnnotation(joinPoint.getTarget().getClass(), RequiresFeature.class);
        }
        if (annotation == null) {
            // Should be unreachable given the pointcut, but never block a request over a
            // resolution quirk - proceed exactly as if this aspect didn't exist.
            return joinPoint.proceed();
        }

        UUID branchId = branchAccessService.resolveEffectiveBranchId(currentUser(), requestedBranchId());
        Subscription subscription = subscriptionRepository.findByBranchId(branchId)
                .orElseThrow(() -> ApiException.notFound(
                        "No subscription configured for this branch yet. Contact Bistrodesk support."));
        if (!entitlementService.isFeatureEnabled(subscription, annotation.value())) {
            throw ApiException.forbidden("This feature ('" + annotation.value() + "') is not included in this "
                    + "branch's current plan (" + subscription.getPlan().getName() + "). Contact Bistrodesk "
                    + "support to upgrade.");
        }
        return joinPoint.proceed();
    }

    private AppUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            return null;
        }
        return branchAccessService.resolve(principal);
    }

    /** An explicit {@code ?branchId=} query parameter, when the current request happens to carry
     * one (many GET endpoints do; most POST/PATCH bodies carry it inside JSON instead, invisible
     * here by design - see this class's own javadoc). Null when there is none, or when this aspect
     * somehow runs outside an HTTP request (never expected for a {@code @RestController} method,
     * but resolved defensively rather than letting a {@code ClassCastException} surface as a
     * confusing 500). */
    private UUID requestedBranchId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        String raw = attrs.getRequest().getParameter("branchId");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
