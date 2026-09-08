package com.chefpay.server.platform;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Feature;
import com.chefpay.core.domain.Permission;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.domain.Role;
import com.chefpay.core.domain.Subscription;
import com.chefpay.core.domain.SubscriptionPlan;
import com.chefpay.core.domain.SubscriptionStatus;
import com.chefpay.core.domain.SupportSettings;
import com.chefpay.core.domain.ThemeSettings;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.FeatureRepository;
import com.chefpay.core.repository.PermissionRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.core.repository.RoleRepository;
import com.chefpay.core.repository.SubscriptionPlanRepository;
import com.chefpay.core.repository.SubscriptionRepository;
import com.chefpay.core.repository.SupportSettingsRepository;
import com.chefpay.core.repository.ThemeSettingsRepository;
import com.chefpay.core.service.EntitlementService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.SubscriptionDtos;
import com.chefpay.server.support.SupportDtos;
import com.chefpay.server.tables.TableSeedingService;
import com.chefpay.server.theme.ThemeDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2's "platform owner" backend (items 16-28, Section A conflict #1 of
 * PHASE2_ORG_SUBSCRIPTION_DESIGN.md): the ChefPay company's own control point over ONE install's
 * licensing, reached from outside that install's normal authentication system entirely. There is no
 * shared central server under the confirmed one-deployment-per-business hosting model, so instead of
 * a login, every request here must present the exact value of {@code CHEFPAY_PLATFORM_OWNER_KEY}
 * (configured per install as an environment variable, known only to ChefPay) in the {@code
 * X-Platform-Owner-Key} header. This is deliberately NOT an {@code AppUser}/{@code Role}/permission
 * of any kind - there is no grant a restaurant's own Owner/Admin, however powerful, could ever hold
 * that reaches this controller; only a completely separate credential does, generated at deploy
 * time and never stored in this database.
 *
 * <p>If the key is not configured at all (blank), every request here is refused - a missing secret
 * must never silently mean "open to anyone," the opposite of a misconfigured-but-still-closed
 * default every other credential-shaped config value in this codebase already follows (see {@code
 * Restaurant#aiApiKey}'s neighbors).
 */
@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
public class PlatformOwnerController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final FeatureRepository featureRepository;
    private final BranchRepository branchRepository;
    private final RestaurantRepository restaurantRepository;
    private final EntitlementService entitlementService;
    /** Bistrodesk follow-up requirement #5: {@code createBranch} below must seed this new branch's
     * default table setup itself - see {@link TableSeedingService#ensureDefaultTableSetup}'s own
     * javadoc for why. */
    private final TableSeedingService tableSeedingService;
    /** Bistrodesk follow-up requirement #7 ("move the role and permission to admin portal... remove
     * completely from application"): backs the new Roles &amp; Permissions tab below - see {@link
     * #listRoles}/{@link #updateRolePermissions}/{@link #listPermissions}'s own javadoc. */
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final ThemeSettingsRepository themeSettingsRepository;
    private final SupportSettingsRepository supportSettingsRepository;

    @Value("${chefpay.platform.owner-key:}")
    private String configuredKey;

    @GetMapping("/plans")
    public ApiResponse<List<SubscriptionDtos.PlanDto>> listPlans(@RequestHeader("X-Platform-Owner-Key") String key) {
        requireValidKey(key);
        return ApiResponse.ok(subscriptionPlanRepository.findAll().stream()
                .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                .map(this::toPlanDto).toList());
    }

    @PostMapping("/plans")
    public ApiResponse<SubscriptionDtos.PlanDto> createPlan(@RequestHeader("X-Platform-Owner-Key") String key,
                                                              @RequestBody PlatformOwnerDtos.CreateOrUpdatePlanRequest request) {
        requireValidKey(key);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Plan name is required.");
        }
        if (request.durationDays() == null || request.durationDays() <= 0) {
            throw ApiException.badRequest("VALIDATION_ERROR", "durationDays must be a positive number of days.");
        }
        SubscriptionPlan plan = SubscriptionPlan.builder()
                .name(request.name())
                .description(request.description())
                .durationDays(request.durationDays())
                .price(request.price() == null ? BigDecimal.ZERO : request.price())
                .gstPercent(request.gstPercent() == null ? BigDecimal.ZERO : request.gstPercent())
                .maxBranches(request.maxBranches())
                .maxTerminals(request.maxTerminals())
                .maxUsers(request.maxUsers())
                .trial(request.trial() != null && request.trial())
                .active(request.active() == null || request.active())
                .displayOrder(request.displayOrder() == null ? 0 : request.displayOrder())
                .features(resolveFeatures(request.featureCodes()))
                .build();
        return ApiResponse.ok(toPlanDto(subscriptionPlanRepository.save(plan)));
    }

    /** Partial update - any null field leaves that attribute unchanged, except {@code
     * featureCodes}, which (when present) replaces the whole set. {@code version}, if supplied,
     * optimistic-locks the same way every other Phase 2 update endpoint does; omitted (null) skips
     * that check - the platform-owner tool is a single trusted operator, not a multi-terminal UI
     * where concurrent edits are the norm, so this is more lenient than the restaurant-facing
     * endpoints on that one point. */
    @PatchMapping("/plans/{id}")
    public ApiResponse<SubscriptionDtos.PlanDto> updatePlan(@RequestHeader("X-Platform-Owner-Key") String key,
                                                              @PathVariable UUID id,
                                                              @RequestBody PlatformOwnerDtos.CreateOrUpdatePlanRequest request) {
        requireValidKey(key);
        SubscriptionPlan plan = subscriptionPlanRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Plan not found"));
        if (request.version() != null && plan.getVersion() != request.version()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(SubscriptionPlan.class, id);
        }
        if (request.name() != null && !request.name().isBlank()) plan.setName(request.name());
        if (request.description() != null) plan.setDescription(request.description());
        if (request.durationDays() != null) plan.setDurationDays(request.durationDays());
        if (request.price() != null) plan.setPrice(request.price());
        if (request.gstPercent() != null) plan.setGstPercent(request.gstPercent());
        if (request.maxBranches() != null) plan.setMaxBranches(request.maxBranches());
        if (request.maxTerminals() != null) plan.setMaxTerminals(request.maxTerminals());
        if (request.maxUsers() != null) plan.setMaxUsers(request.maxUsers());
        if (request.trial() != null) plan.setTrial(request.trial());
        if (request.active() != null) plan.setActive(request.active());
        if (request.displayOrder() != null) plan.setDisplayOrder(request.displayOrder());
        if (request.featureCodes() != null) plan.setFeatures(resolveFeatures(request.featureCodes()));
        return ApiResponse.ok(toPlanDto(subscriptionPlanRepository.save(plan)));
    }

    @GetMapping("/features")
    public ApiResponse<List<Feature>> listFeatures(@RequestHeader("X-Platform-Owner-Key") String key) {
        requireValidKey(key);
        return ApiResponse.ok(featureRepository.findAll());
    }

    @PostMapping("/features")
    public ApiResponse<Feature> createFeature(@RequestHeader("X-Platform-Owner-Key") String key,
                                                @RequestBody PlatformOwnerDtos.CreateFeatureRequest request) {
        requireValidKey(key);
        if (request.code() == null || request.code().isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Feature code is required.");
        }
        if (featureRepository.findByCodeIgnoreCase(request.code()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_FEATURE", "A feature with that code already exists.");
        }
        return ApiResponse.ok(featureRepository.save(
                Feature.builder().code(request.code().trim().toUpperCase()).description(request.description()).build()));
    }

    /** Bistrodesk Phase 1: {@code branchId} is now required - {@link Subscription} is keyed by
     * branch, not restaurant (see that entity's javadoc). */
    @GetMapping("/subscription")
    public ApiResponse<SubscriptionDtos.SubscriptionDto> getSubscription(@RequestHeader("X-Platform-Owner-Key") String key,
                                                                          @RequestParam UUID branchId) {
        requireValidKey(key);
        Subscription subscription = subscriptionRepository.findByBranchId(branchId)
                .orElseThrow(() -> ApiException.notFound("No subscription exists for this branch yet."));
        return ApiResponse.ok(toSubscriptionDto(subscription));
    }

    /** Bistrodesk Phase 1: every branch's subscription at once, keyed by branch id, for a
     * Bistrodesk Admin UI that lists an install's branches with their plan/status side by side
     * rather than requiring one request per branch (the real UI for this is Phase 4's job - this
     * endpoint just makes the data available). */
    @GetMapping("/subscriptions")
    public ApiResponse<List<SubscriptionDtos.SubscriptionDto>> listSubscriptions(@RequestHeader("X-Platform-Owner-Key") String key) {
        requireValidKey(key);
        return ApiResponse.ok(subscriptionRepository.findAll().stream()
                .filter(s -> s.getBranch() != null)
                .map(this::toSubscriptionDto)
                .toList());
    }

    /** Bistrodesk Phase 4 (requirement #24's Admin UI): every branch on this install, for the
     * "assign/change a branch's plan" screen's branch picker - {@link #listSubscriptions} alone
     * can't drive that picker since a branch with NO subscription yet (a brand-new one - see {@code
     * BranchController#create}'s Phase 4 gating javadoc) would never appear in it at all. */
    @GetMapping("/branches")
    public ApiResponse<List<PlatformOwnerDtos.BranchSummaryDto>> listBranches(@RequestHeader("X-Platform-Owner-Key") String key) {
        requireValidKey(key);
        return ApiResponse.ok(branchRepository.findAllByOrderByNameAsc().stream()
                .map(b -> new PlatformOwnerDtos.BranchSummaryDto(b.getId(), b.getName(), b.getBranchCode(),
                        b.isActive(), subscriptionRepository.findByBranchId(b.getId()).isPresent()))
                .toList());
    }

    /** Bistrodesk branch-isolation release (requirement #2, user-confirmed decision): branch
     * creation moved here from the restaurant-facing {@code POST /api/branches} (removed entirely -
     * see {@code BranchController}'s own javadoc) - no POS role, however senior, may create a branch
     * any more; only this platform-owner-key-gated console can. Reuses the exact same
     * branch-code-generation and {@code MULTI_BRANCH}/plan-capacity gating {@code BranchController
     * #create} used to have, moved here verbatim rather than re-derived, so behavior (a brand-new
     * install's first branch always succeeds; a second or later one needs an existing branch
     * entitled to {@code MULTI_BRANCH} with room under its {@code maxBranches} cap) is unchanged by
     * WHO is allowed to trigger it. */
    @PostMapping("/branches")
    public ApiResponse<PlatformOwnerDtos.BranchSummaryDto> createBranch(@RequestHeader("X-Platform-Owner-Key") String key,
                                                                          @RequestBody PlatformOwnerDtos.CreateBranchRequest request) {
        requireValidKey(key);
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Branch name is required.");
        }
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
        assertCanAddAnotherBranch();
        // Follow-up enhancement ("Local Time Zone During Branch Creation"): an explicitly chosen
        // zone wins; an omitted/blank one defaults to the restaurant's own defaultTimezone so this
        // never leaves a branch with no zone at all, matching V44/DataSeeder's backfill fallback.
        String timezone = (request.timezone() == null || request.timezone().isBlank())
                ? restaurant.getDefaultTimezone() : request.timezone();
        Branch branch = Branch.builder()
                .restaurant(restaurant)
                .name(request.name())
                .address(request.address())
                .phone(request.phone())
                .branchCode(generateBranchCode())
                .active(true)
                .timezone(timezone)
                .build();
        Branch saved = branchRepository.save(branch);
        // Bistrodesk follow-up requirement #5 ("default table setup should assign to every branch
        // while creation"): every branch needs at least one Floor AND at least one table before its
        // own "Add Table" screen can ever add a second one (TablesPage.tsx's AddTableModal derives
        // its target floor from an already-existing table - a branch with zero tables could never
        // create its first one either). See TableSeedingService#ensureDefaultTableSetup's own
        // javadoc for the exact shape seeded and why this call is safe to make unconditionally here.
        tableSeedingService.ensureDefaultTableSetup(saved);
        return ApiResponse.ok(new PlatformOwnerDtos.BranchSummaryDto(saved.getId(), saved.getName(),
                saved.getBranchCode(), saved.isActive(), false));
    }

    /** Moved verbatim from {@code BranchController#assertCanAddAnotherBranch} - see that method's
     * own (now-removed) javadoc, reproduced here: a brand-new install (zero branches yet) always
     * passes; otherwise this restaurant is entitled to add another branch if ANY existing branch's
     * current subscription both includes {@code MULTI_BRANCH} and has room under that plan's {@code
     * maxBranches} cap (null = unlimited). */
    private void assertCanAddAnotherBranch() {
        List<Branch> existing = branchRepository.findAll();
        if (existing.isEmpty()) {
            return;
        }
        int branchCountAfterCreate = existing.size() + 1;
        boolean anyQualifies = existing.stream()
                .map(b -> subscriptionRepository.findByBranchId(b.getId()))
                .flatMap(java.util.Optional::stream)
                .anyMatch((Subscription s) -> entitlementService.isFeatureEnabled(s, "MULTI_BRANCH")
                        && (s.getPlan().getMaxBranches() == null || s.getPlan().getMaxBranches() >= branchCountAfterCreate));
        if (!anyQualifies) {
            throw ApiException.forbidden("This restaurant's current plan does not support adding another branch "
                    + "(either MULTI_BRANCH is not included, or the plan's branch limit has been reached). "
                    + "Upgrade the qualifying branch's plan first.");
        }
    }

    /** Moved verbatim from {@code BranchController#generateBranchCode}. */
    private String generateBranchCode() {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            String candidate = String.format("%04d", RANDOM.nextInt(10_000));
            if (!branchRepository.existsByBranchCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique branch code.");
    }

    /** Creates the given branch's subscription if none exists yet, otherwise updates it - renewal,
     * plan change, grace-period/warning-threshold tuning, and the manual SUSPENDED override all go
     * through this one endpoint, per {@code UpsertSubscriptionRequest}'s javadoc. */
    @PostMapping("/subscription")
    public ApiResponse<SubscriptionDtos.SubscriptionDto> upsertSubscription(
            @RequestHeader("X-Platform-Owner-Key") String key,
            @RequestBody PlatformOwnerDtos.UpsertSubscriptionRequest request) {
        requireValidKey(key);
        if (request.branchId() == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "branchId is required.");
        }
        Branch branch = branchRepository.findById(request.branchId())
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        Subscription subscription = subscriptionRepository.findByBranchId(branch.getId()).orElse(null);

        SubscriptionPlan plan = null;
        if (request.planId() != null) {
            plan = subscriptionPlanRepository.findById(request.planId())
                    .orElseThrow(() -> ApiException.notFound("Plan not found"));
        }

        if (subscription == null) {
            if (plan == null) {
                throw ApiException.badRequest("VALIDATION_ERROR", "planId is required to create a new subscription.");
            }
            LocalDate start = request.startDate() == null ? LocalDate.now() : request.startDate();
            subscription = Subscription.builder()
                    .branch(branch)
                    // @Deprecated legacy column, still NOT NULL in the schema - see
                    // Subscription.restaurant's javadoc for why this is still set.
                    .restaurant(branch.getRestaurant())
                    .plan(plan)
                    .status(SubscriptionStatus.ACTIVE)
                    .startDate(start)
                    .expiryDate(request.expiryDate() == null ? start.plusDays(plan.getDurationDays()) : request.expiryDate())
                    .gracePeriodDays(request.gracePeriodDays() == null ? 3 : request.gracePeriodDays())
                    .warningThresholdsDays(request.warningThresholdsDays() == null
                            ? "30,15,7,3,1" : request.warningThresholdsDays())
                    .supportPhone(request.supportPhone())
                    .build();
        } else {
            if (plan != null) subscription.setPlan(plan);
            if (request.startDate() != null) subscription.setStartDate(request.startDate());
            if (request.expiryDate() != null) subscription.setExpiryDate(request.expiryDate());
            if (request.gracePeriodDays() != null) subscription.setGracePeriodDays(request.gracePeriodDays());
            if (request.warningThresholdsDays() != null) subscription.setWarningThresholdsDays(request.warningThresholdsDays());
            if (request.supportPhone() != null) subscription.setSupportPhone(request.supportPhone());
            if (request.status() != null && !request.status().isBlank()) {
                subscription.setStatus(SubscriptionStatus.valueOf(request.status().trim().toUpperCase()));
            }
        }
        return ApiResponse.ok(toSubscriptionDto(subscriptionRepository.save(subscription)));
    }

    /** Follow-up enhancement ("Subscription Plan Reactivation": "Add the ability to reactivate a
     * subscription plan when required."). See {@link PlatformOwnerDtos.ReactivateSubscriptionRequest}'s
     * javadoc for why this is a dedicated, narrower endpoint rather than reusing {@link
     * #upsertSubscription}'s generic {@code status}/{@code expiryDate} fields directly - the short
     * version: {@code EntitlementService#refreshStatus} recomputes status from {@code expiryDate} on
     * every check, so forcing {@code status=ACTIVE} alone on an already-expired subscription doesn't
     * durably stick. This always does both together: pushes {@code expiryDate} into the future (a
     * caller-supplied {@code newExpiryDate}, or "today + the current plan's durationDays" if
     * omitted) AND sets {@code status=ACTIVE}, so a reactivated subscription actually stays active
     * on the very next entitlement check. */
    @PostMapping("/subscriptions/{branchId}/reactivate")
    public ApiResponse<SubscriptionDtos.SubscriptionDto> reactivateSubscription(
            @RequestHeader("X-Platform-Owner-Key") String key,
            @PathVariable UUID branchId,
            @RequestBody(required = false) PlatformOwnerDtos.ReactivateSubscriptionRequest request) {
        requireValidKey(key);
        Subscription subscription = subscriptionRepository.findByBranchId(branchId)
                .orElseThrow(() -> ApiException.notFound("No subscription found for this branch."));
        if (request != null && request.version() != null && subscription.getVersion() != request.version()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Subscription.class, subscription.getId());
        }
        LocalDate today = LocalDate.now();
        LocalDate newExpiry = (request != null && request.newExpiryDate() != null)
                ? request.newExpiryDate()
                : today.plusDays(subscription.getPlan().getDurationDays());
        if (!newExpiry.isAfter(today)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "The new expiry date must be in the future.");
        }
        subscription.setExpiryDate(newExpiry);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        return ApiResponse.ok(toSubscriptionDto(subscriptionRepository.save(subscription)));
    }

    /** Bistrodesk follow-up requirement #7 ("move the role and permission to admin portal which
     * can be modified only by bistrodesk team or admin. remove completely from application"): the
     * full role list with each one's current permission set, for the admin console's Roles &amp;
     * Permissions tab. The POS-facing {@code GET /api/roles} (still {@code USER_VIEW}/{@code
     * USER_MANAGE}/{@code ROLE_MANAGE}-gated) stays read-only and reachable for its own unrelated
     * purpose (an Owner/Manager picking a role name for a new staff account) - only the ability to
     * EDIT a role's permissions moves here. */
    @GetMapping("/roles")
    public ApiResponse<List<PlatformOwnerDtos.RoleSummaryDto>> listRoles(@RequestHeader("X-Platform-Owner-Key") String key) {
        requireValidKey(key);
        return ApiResponse.ok(roleRepository.findAll().stream().map(this::toRoleDto).toList());
    }

    /** Moved verbatim from the removed {@code com.chefpay.server.users.RoleController
     * #updatePermissions} (previously reachable by any POS account holding {@code ROLE_MANAGE} -
     * OWNER/ADMIN by default, see {@code DataSeeder#PERMISSION_CODES}) - full-replace semantics,
     * optimistic-locked exactly like that removed endpoint was. */
    @PatchMapping("/roles/{id}/permissions")
    public ApiResponse<PlatformOwnerDtos.RoleSummaryDto> updateRolePermissions(
            @RequestHeader("X-Platform-Owner-Key") String key,
            @PathVariable UUID id,
            @RequestBody PlatformOwnerDtos.UpdateRolePermissionsRequest request) {
        requireValidKey(key);
        Role role = roleRepository.findById(id).orElseThrow(() -> ApiException.notFound("Role not found"));
        if (request.version() != null && role.getVersion() != request.version()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Role.class, id);
        }
        Set<Permission> permissions = new HashSet<>();
        for (String code : request.permissionCodes() == null ? List.<String>of() : request.permissionCodes()) {
            permissions.add(permissionRepository.findByCode(code)
                    .orElseThrow(() -> ApiException.badRequest("UNKNOWN_PERMISSION", "Unknown permission code: " + code)));
        }
        role.setPermissions(permissions);
        return ApiResponse.ok(toRoleDto(roleRepository.save(role)));
    }

    /** The master permission catalog every role's checklist is built from - moved verbatim from
     * the removed {@code com.chefpay.server.users.PermissionController#list}. */
    @GetMapping("/permissions")
    public ApiResponse<List<PlatformOwnerDtos.PermissionSummaryDto>> listPermissions(@RequestHeader("X-Platform-Owner-Key") String key) {
        requireValidKey(key);
        return ApiResponse.ok(permissionRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Permission::getCode))
                .map(p -> new PlatformOwnerDtos.PermissionSummaryDto(p.getId(), p.getCode(), p.getDescription()))
                .toList());
    }

    /** Follow-up requirement ("Move Appearance Settings to Admin Portal": "Only the BistroDesk
     * team/admin should have permission to modify these settings. Branch-level users should not be
     * able to change the application's global appearance."). Same migration shape as Roles &amp;
     * Permissions above and branch creation before that: {@code GET /api/theme} stays reachable
     * from the POS app (every terminal/KDS/dashboard still needs to render with the current theme -
     * see {@code ThemeController#get}'s own javadoc for why that read has no permission gate at
     * all), but the WRITE moved here entirely - {@code ThemeController#update} (previously
     * reachable by any POS account holding {@code RESTAURANT_MANAGE}, i.e. OWNER/ADMIN/Manager by
     * default) was removed outright, no POS role can change the global theme any more. Reuses
     * {@link ThemeDtos} directly (not a duplicated platform-only copy) - same precedent as this
     * controller already reusing {@link SubscriptionDtos} for Plans/Subscriptions, since the shape
     * carries no auth-specific fields worth decoupling. */
    @GetMapping("/theme")
    public ApiResponse<ThemeDtos.ThemeSettingsDto> getTheme(@RequestHeader("X-Platform-Owner-Key") String key) {
        requireValidKey(key);
        return ApiResponse.ok(toThemeDto(loadOrCreateTheme()));
    }

    @PutMapping("/theme")
    public ApiResponse<ThemeDtos.ThemeSettingsDto> updateTheme(@RequestHeader("X-Platform-Owner-Key") String key,
                                                                @RequestBody ThemeDtos.UpdateThemeRequest request) {
        requireValidKey(key);
        ThemeSettings settings = loadOrCreateTheme();
        if (settings.getVersion() != request.version()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(ThemeSettings.class, settings.getId());
        }
        // Empty string clears back to "no customization" (same clears-on-blank convention the
        // removed ThemeController#update had), null leaves unchanged.
        if (request.themeJson() != null) {
            settings.setThemeJson(request.themeJson().isBlank() ? null : request.themeJson());
        }
        return ApiResponse.ok(toThemeDto(themeSettingsRepository.save(settings)));
    }

    private ThemeSettings loadOrCreateTheme() {
        return themeSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> themeSettingsRepository.save(ThemeSettings.builder().build()));
    }

    private ThemeDtos.ThemeSettingsDto toThemeDto(ThemeSettings settings) {
        return new ThemeDtos.ThemeSettingsDto(settings.getThemeJson(), settings.getVersion());
    }

    /** Requirement ("Add a small 'Help' icon..."): "Admin Panel (BistroDesk): Add a new Support
     * &amp; Policy Configuration section where the admin can configure/update the support phone
     * number, configure/update the support email ID, add/edit Terms &amp; Conditions, add/edit
     * Privacy/Policy content." Same migration shape as Appearance above: {@code GET /api/support}
     * stays reachable from the POS app for the Help popup (see {@code SupportController#get}'s own
     * javadoc for why that read has no permission gate at all), but the write lives here only,
     * gated by the platform-owner key - no POS role can change support/policy content. */
    @GetMapping("/support")
    public ApiResponse<SupportDtos.SupportSettingsDto> getSupport(@RequestHeader("X-Platform-Owner-Key") String key) {
        requireValidKey(key);
        return ApiResponse.ok(toSupportDto(loadOrCreateSupport()));
    }

    @PutMapping("/support")
    public ApiResponse<SupportDtos.SupportSettingsDto> updateSupport(
            @RequestHeader("X-Platform-Owner-Key") String key,
            @RequestBody SupportDtos.UpdateSupportSettingsRequest request) {
        requireValidKey(key);
        SupportSettings settings = loadOrCreateSupport();
        if (request.version() != null && settings.getVersion() != request.version()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(SupportSettings.class, settings.getId());
        }
        // Empty string clears the field back to unset (same clears-on-blank convention
        // updateTheme's themeJson handling uses), null leaves it unchanged.
        if (request.supportPhone() != null) {
            settings.setSupportPhone(request.supportPhone().isBlank() ? null : request.supportPhone());
        }
        if (request.supportEmail() != null) {
            settings.setSupportEmail(request.supportEmail().isBlank() ? null : request.supportEmail());
        }
        if (request.termsAndConditions() != null) {
            settings.setTermsAndConditions(request.termsAndConditions().isBlank() ? null : request.termsAndConditions());
        }
        if (request.privacyPolicy() != null) {
            settings.setPrivacyPolicy(request.privacyPolicy().isBlank() ? null : request.privacyPolicy());
        }
        return ApiResponse.ok(toSupportDto(supportSettingsRepository.save(settings)));
    }

    private SupportSettings loadOrCreateSupport() {
        return supportSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> supportSettingsRepository.save(SupportSettings.builder().build()));
    }

    private SupportDtos.SupportSettingsDto toSupportDto(SupportSettings settings) {
        return new SupportDtos.SupportSettingsDto(settings.getSupportPhone(), settings.getSupportEmail(),
                settings.getTermsAndConditions(), settings.getPrivacyPolicy(), settings.getVersion());
    }

    private PlatformOwnerDtos.RoleSummaryDto toRoleDto(Role role) {
        List<String> codes = role.getPermissions().stream().map(Permission::getCode).sorted().toList();
        return new PlatformOwnerDtos.RoleSummaryDto(role.getId(), role.getName(), role.getDescription(), codes, role.getVersion());
    }

    private Set<Feature> resolveFeatures(List<String> codes) {
        if (codes == null) {
            return new HashSet<>();
        }
        Set<Feature> features = new HashSet<>();
        for (String code : codes) {
            features.add(featureRepository.findByCodeIgnoreCase(code)
                    .orElseThrow(() -> ApiException.badRequest("UNKNOWN_FEATURE", "Unknown feature code: " + code)));
        }
        return features;
    }

    private SubscriptionDtos.PlanDto toPlanDto(SubscriptionPlan p) {
        return new SubscriptionDtos.PlanDto(p.getId(), p.getName(), p.getDescription(), p.getDurationDays(),
                p.getPrice(), p.getGstPercent(), p.getMaxBranches(), p.getMaxTerminals(), p.getMaxUsers(),
                p.isTrial(), p.isActive(), p.getDisplayOrder(),
                p.getFeatures().stream().map(Feature::getCode).sorted().toList());
    }

    private SubscriptionDtos.SubscriptionDto toSubscriptionDto(Subscription s) {
        long remainingDays = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), s.getExpiryDate());
        return new SubscriptionDtos.SubscriptionDto(s.getId(),
                s.getBranch() == null ? null : s.getBranch().getId(),
                s.getBranch() == null ? null : s.getBranch().getName(),
                s.getPlan().getName(), s.getPlan().getId(),
                s.getStatus().name(), s.getStartDate(), s.getExpiryDate(), remainingDays, s.getGracePeriodDays(),
                s.getWarningThresholdsDays(), s.getSupportPhone(), s.getVersion());
    }

    /** Constant-time comparison (never {@code String#equals}, which short-circuits on the first
     * mismatched character - a timing side-channel for a secret this sensitive) against the
     * per-install {@code CHEFPAY_PLATFORM_OWNER_KEY}. Refuses everything if that key isn't
     * configured at all - see this class's own javadoc. */
    private void requireValidKey(String providedKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw ApiException.forbidden(
                    "The platform-owner endpoint is not configured on this install (CHEFPAY_PLATFORM_OWNER_KEY is unset).");
        }
        if (providedKey == null || providedKey.isBlank()
                || !MessageDigest.isEqual(providedKey.getBytes(StandardCharsets.UTF_8), configuredKey.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("Invalid platform-owner key.");
        }
    }
}
