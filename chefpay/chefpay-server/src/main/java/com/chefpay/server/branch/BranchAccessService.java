package com.chefpay.server.branch;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bistrodesk Phase 1: generalizes the branch-isolation check {@code PurchaseOrderController}
 * pioneered (its own {@code assertBranchAccess}/{@code filterToAccessibleBranches}, Round 12 §23) -
 * confirmed, before writing this, to be the ONLY controller in the whole codebase that actually
 * enforces branch access at all - into one shared bean every controller can call, instead of
 * re-implementing (or, as found, simply never implementing) the same check per controller.
 *
 * <p><b>Deliberately resolves the caller's {@link AppUser#getBranches()} fresh from the database
 * on every call</b>, exactly like the precedent it generalizes, rather than embedding a branch
 * list in the JWT the way {@code JwtAuthenticationFilter} already does for permission codes. This
 * is a considered deviation from the original phase plan's wording ("add branch context to the
 * JWT/AuthenticatedPrincipal"), made after reading the actual precedent: permission codes change
 * rarely (an admin edits a Role) and this app already accepts up to 12h of staleness for those
 * (see {@code JwtService}'s expiry) since there is no refresh-token flow; branch ASSIGNMENT is
 * edited far more often in practice (moving a cashier between branches), and re-issuing every
 * affected user's token isn't a real option here. Resolving fresh means a branch reassignment (or a
 * newly-granted {@link #VIEW_ALL_BRANCHES} permission) takes effect on the caller's very next
 * request, not up to 12h later - strictly better for a security-relevant check, at the cost of one
 * extra indexed lookup by primary key per request, which every existing branch-checked endpoint
 * already paid.
 *
 * <p>Empty {@link AppUser#getBranches()} = unrestricted ("every branch"), the existing convention
 * every current single-branch install already relies on (zero rows in {@code app_user_branch}).
 * Going forward, the explicit way to grant cross-branch visibility to a user who DOES have specific
 * branches assigned is the new {@link #VIEW_ALL_BRANCHES} permission (requirement #28's "central
 * access" concept) - see {@link #isUnrestricted(AppUser)}.
 */
@Service
@RequiredArgsConstructor
public class BranchAccessService {

    /** Requirement #28's explicit central-access permission: replaces the old *accidental*
     * "empty branches = unrestricted" convention as the thing that actually grants cross-branch
     * visibility, for any user who has specific branches assigned. Granted to OWNER/ADMIN by
     * default (see {@code DataSeeder#PERMISSION_CODES}) - MANAGER stays branch-scoped, matching its
     * existing "runs the day-to-day Branches & Terminals screen but not org/subscription-level
     * settings" tier. */
    public static final String VIEW_ALL_BRANCHES = "VIEW_ALL_BRANCHES";

    private final AppUserRepository appUserRepository;
    private final BranchRepository branchRepository;

    /** Same null-tolerant resolution {@code PurchaseOrderController#requester} already used - a
     * principal that fails to resolve to a real {@link AppUser} row is treated the same permissive
     * way the existing precedent already treats it, in {@link #isUnrestricted}, rather than this
     * method throwing. */
    public AppUser resolve(AuthenticatedPrincipal principal) {
        if (principal == null) {
            return null;
        }
        return appUserRepository.findById(principal.userId()).orElse(null);
    }

    public boolean isUnrestricted(AppUser user) {
        if (user == null || user.getBranches().isEmpty()) {
            return true;
        }
        return currentAuthorities().contains(VIEW_ALL_BRANCHES);
    }

    /** Throws a 404 (never a 403) unless {@code branchId} is one this user may access - matching
     * the precedent's own reasoning: a branch a caller cannot access should look exactly like a
     * branch that does not exist, rather than confirming its existence to someone not allowed to
     * see it. A null {@code branchId} is never itself a violation (many endpoints treat "no branch
     * specified" as "show me everything I can see" - see {@link #accessibleBranchIds}) - callers
     * that require a non-null branch check that separately. */
    public void assertAccess(AppUser user, UUID branchId) {
        if (isUnrestricted(user) || branchId == null) {
            return;
        }
        boolean allowed = user.getBranches().stream().anyMatch(b -> b.getId().equals(branchId));
        if (!allowed) {
            throw ApiException.notFound("Branch not found");
        }
    }

    public void assertAccess(AuthenticatedPrincipal principal, UUID branchId) {
        assertAccess(resolve(principal), branchId);
    }

    /** {@code null} means "unrestricted, every branch" - callers pass this straight through as
     * "don't filter" rather than enumerating every branch id that happens to exist right now (which
     * would silently go stale the moment a new branch is created). */
    public Set<UUID> accessibleBranchIds(AppUser user) {
        if (isUnrestricted(user)) {
            return null;
        }
        return user.getBranches().stream().map(Branch::getId).collect(Collectors.toSet());
    }

    public Set<UUID> accessibleBranchIds(AuthenticatedPrincipal principal) {
        return accessibleBranchIds(resolve(principal));
    }

    /** Bistrodesk Phase 4: resolves the exactly-ONE branch a request is "about," for a check that
     * needs a single branch rather than this user's whole accessible set (today: {@code
     * com.chefpay.server.subscription.RequiresFeatureAspect}'s entitlement gate, and {@code
     * SubscriptionController}'s own read endpoints, refactored onto this shared method rather than
     * keeping their own copy of the same fallback chain). Order: an explicit {@code
     * requestedBranchId} (access-checked) wins; else the
     * caller's own configured default branch; else their one and only accessible branch; else (the
     * common single-branch install, where staff often have no branch assignment configured at all)
     * this install's one and only {@link Branch}. Throws a clear {@code BRANCH_REQUIRED} error
     * rather than guessing among several candidates when a check this consequential (billing
     * entitlement, subscription status) has no way to know which one branch is meant. */
    public UUID resolveEffectiveBranchId(AppUser user, UUID requestedBranchId) {
        if (requestedBranchId != null) {
            assertAccess(user, requestedBranchId);
            return requestedBranchId;
        }
        if (user != null && user.getDefaultBranch() != null) {
            return user.getDefaultBranch().getId();
        }
        if (user != null && user.getBranches().size() == 1) {
            return user.getBranches().iterator().next().getId();
        }
        List<Branch> allBranches = branchRepository.findAll();
        if (allBranches.size() == 1) {
            return allBranches.get(0).getId();
        }
        throw ApiException.badRequest("BRANCH_REQUIRED",
                "Specify which branch this request is for (this restaurant has more than one branch "
                        + "and this account has no default branch configured).");
    }

    public UUID resolveEffectiveBranchId(AuthenticatedPrincipal principal, UUID requestedBranchId) {
        return resolveEffectiveBranchId(resolve(principal), requestedBranchId);
    }

    /** Bistrodesk Phase 5: resolves the branch-id FILTER for a report/dashboard-style endpoint,
     * where showing "every branch I'm allowed to see" is a normal, common request - not an error
     * the way an ambiguous {@link #resolveEffectiveBranchId} call is. {@code null} back means "no
     * filter" (an unrestricted caller who didn't drill into one specific branch); a non-null
     * {@link Set} means restrict to exactly those branch ids - either the caller's own accessible
     * set (the default "show me my own branch(es)" behavior every restricted user gets with no
     * query param at all), or exactly one branch when {@code requestedBranchId} is supplied
     * (access-checked the same way {@link #assertAccess} always has, so a restricted user still
     * can't drill into a branch outside their own set just by naming its id). */
    public Set<UUID> resolveBranchFilter(AppUser user, UUID requestedBranchId) {
        if (requestedBranchId != null) {
            assertAccess(user, requestedBranchId);
            return Set.of(requestedBranchId);
        }
        return accessibleBranchIds(user);
    }

    public Set<UUID> resolveBranchFilter(AuthenticatedPrincipal principal, UUID requestedBranchId) {
        return resolveBranchFilter(resolve(principal), requestedBranchId);
    }

    /** Generalizes {@code PurchaseOrderController#filterToAccessibleBranches} to any item type via
     * a branch-id extractor function, so each phase-2 controller doesn't re-implement the same
     * null-means-unrestricted filtering loop. */
    public <T> List<T> filterToAccessibleBranches(AppUser user, List<T> items, Function<T, UUID> branchIdExtractor) {
        Set<UUID> allowed = accessibleBranchIds(user);
        if (allowed == null) {
            return items;
        }
        return items.stream().filter(item -> allowed.contains(branchIdExtractor.apply(item))).toList();
    }

    private Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toSet());
    }
}
