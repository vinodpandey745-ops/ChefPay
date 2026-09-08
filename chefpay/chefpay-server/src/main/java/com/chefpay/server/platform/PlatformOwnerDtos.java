package com.chefpay.server.platform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class PlatformOwnerDtos {

    private PlatformOwnerDtos() {
    }

    /** Any null field (other than the required ones on create) leaves that attribute unchanged on
     * an edit - same convention as every other update request in this codebase. {@code
     * featureCodes}, when present, REPLACES the plan's whole feature set (not additive) - simplest
     * to reason about from the platform-owner's plan-editor screen, which always shows/submits the
     * complete checked list. */
    public record CreateOrUpdatePlanRequest(
            String name,
            String description,
            Integer durationDays,
            BigDecimal price,
            BigDecimal gstPercent,
            Integer maxBranches,
            Integer maxTerminals,
            Integer maxUsers,
            Boolean trial,
            Boolean active,
            Integer displayOrder,
            List<String> featureCodes,
            Long version
    ) {
    }

    public record CreateFeatureRequest(String code, String description) {
    }

    /** Bistrodesk Phase 4 (requirement #24's Admin UI): a lightweight branch picker row for the
     * "assign a plan to a branch" screen - deliberately its own small DTO here rather than reusing
     * {@code com.chefpay.server.branches.BranchDto} (that one's shape is owned by the restaurant's
     * own Branches screen and carries fields - {@code terminalCount} - this screen doesn't need).
     * {@code hasSubscription} lets the Admin UI visually flag a branch that was created but never
     * had a plan assigned yet (a real, reachable state - see {@code BranchController#create}'s
     * Phase 4 gating javadoc for why a brand-new install's first branch can exist with none). */
    public record BranchSummaryDto(
            UUID id,
            String name,
            String branchCode,
            boolean active,
            boolean hasSubscription
    ) {
    }

    /** Bistrodesk branch-isolation release (requirement #2, user-confirmed decision: branch
     * creation is a Bistrodesk-Admin-only action - no POS role, however senior, can create a branch
     * any more). This is now the ONLY branch-creation request shape in the codebase - the POS-side
     * {@code com.chefpay.server.branches.CreateBranchRequest} it used to mirror was deleted along
     * with {@code BranchController}'s create endpoint. {@code branchCode} is never accepted here
     * either, it is always server-generated.
     *
     * <p>Follow-up enhancement ("Local Time Zone During Branch Creation"): {@code timezone} is an
     * optional IANA zone id for the new branch (e.g. {@code "America/New_York"}) - when omitted or
     * blank, {@code createBranch} below defaults it to the restaurant's own {@code
     * defaultTimezone}, so every branch still starts with a real zone even from an older admin
     * console client that doesn't send this field yet. */
    public record CreateBranchRequest(
            String name,
            String address,
            String phone,
            String timezone
    ) {
    }

    /**
     * Creates the branch's Subscription if none exists yet, or updates the existing one otherwise
     * (upsert - there is exactly one Subscription row per BRANCH as of Bistrodesk Phase 1, see
     * {@code Subscription}'s javadoc for why this moved off "per install"). {@code branchId} is
     * required - it is how this upsert knows WHICH branch's subscription to create/update, now that
     * a single install can have several. {@code startDate} null on an upsert leaves the existing
     * start date unchanged; on a genuine first-create it defaults to today. {@code status}, if
     * supplied, is the ONLY way to force {@link com.chefpay.core.domain.SubscriptionStatus#SUSPENDED}
     * (or clear it back to a normal computed state) - see {@code EntitlementService#refreshStatus}'s
     * javadoc for why every other status value is otherwise server-computed, never client-set.
     */
    public record UpsertSubscriptionRequest(
            UUID branchId,
            UUID planId,
            LocalDate startDate,
            LocalDate expiryDate,
            Integer gracePeriodDays,
            String warningThresholdsDays,
            String supportPhone,
            String status
    ) {
    }

    /** Follow-up enhancement ("Subscription Plan Reactivation": "Add the ability to reactivate a
     * subscription plan when required."). Deliberately a SEPARATE, narrower action from {@link
     * UpsertSubscriptionRequest} above rather than reusing its generic {@code status}/{@code
     * expiryDate} fields directly: {@code EntitlementService#refreshStatus} unconditionally
     * recomputes any non-SUSPENDED status from today vs. {@code expiryDate} on every single
     * entitlement check, so forcing {@code status=ACTIVE} through the generic upsert WITHOUT also
     * pushing {@code expiryDate} into the future looks like it worked for one request, then silently
     * flips straight back to EXPIRED/GRACE_PERIOD on the very next check - a real trap the admin
     * console's own "Status Override" dropdown had. This request shape makes that mistake
     * impossible: {@code newExpiryDate} null/omitted defaults to "today + the current plan's
     * durationDays" ({@code PlatformOwnerController#reactivateSubscription}), and status is ALWAYS
     * set to ACTIVE, never left to the caller. {@code version} is optional, optimistic-locked the
     * same way every other write here is when supplied. */
    public record ReactivateSubscriptionRequest(
            LocalDate newExpiryDate,
            Long version
    ) {
    }

    /** Bistrodesk follow-up requirement #7 ("move the role and permission to admin portal which
     * can be modified only by bistrodesk team or admin. remove completely from application"):
     * moved here verbatim from the now-read-only {@code com.chefpay.server.users.RoleDto} - see
     * {@code RoleController}'s own javadoc for why the POS-reachable API keeps a read-only {@code
     * GET /api/roles} (the "Add Staff"/"Edit Staff" role picker still needs role names) but no
     * longer accepts writes to a role's permission set at all. */
    public record RoleSummaryDto(UUID id, String name, String description, List<String> permissionCodes, long version) {
    }

    /** Same write shape the removed {@code com.chefpay.server.users.UpdateRolePermissionsRequest}
     * had - full-replace, not additive, matching the admin console's own "always shows/submits the
     * complete checked list" convention every other editor there already uses (see {@code
     * openPlanModal}'s feature checkboxes in admin/index.html for the identical pattern). */
    public record UpdateRolePermissionsRequest(List<String> permissionCodes, Long version) {
    }

    /** The master permission catalog - every grantable code, for the Roles &amp; Permissions tab's
     * checklist. Mirrors {@code com.chefpay.server.users.PermissionDto} exactly. */
    public record PermissionSummaryDto(UUID id, String code, String description) {
    }
}
