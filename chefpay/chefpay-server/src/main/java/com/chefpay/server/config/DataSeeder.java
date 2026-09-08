package com.chefpay.server.config;

import com.chefpay.core.domain.*;
import com.chefpay.core.repository.*;
import com.chefpay.core.service.UserAccountService;
import com.chefpay.server.tables.TableSeedingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Two independent jobs, both idempotent but with different re-run rules:
 *
 * <ol>
 *   <li>{@link #ensurePermissionsAndRoles()} runs on EVERY startup, and upserts by code/name
 *       rather than an all-or-nothing skip. New phases keep adding permission codes to
 *       {@link #PERMISSION_CODES} - without this, anyone who already has a seeded database from
 *       an earlier phase would never receive those new codes (the old all-or-nothing "restaurant
 *       already exists, skip everything" check would silently skip them forever), and every
 *       endpoint gated behind a newly-added permission would 403 for otherwise-correct
 *       ADMIN/MANAGER/etc. users. This only ever ADDS missing grants to a role - it never removes
 *       a permission a role already has, so manual customization via {@code PATCH
 *       /api/roles/{id}/permissions} is never clobbered by a restart.</li>
 *   <li>{@link #ensureDemoRestaurantData()} keeps the original one-time-only behavior (skips
 *       entirely once a restaurant row exists) - the demo restaurant/branch/floor/tables/menu/
 *       admin-login are a first-run convenience, not something that should keep reappearing or
 *       get re-added on every phase.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private static final List<String> PERMISSION_CODES = List.of(
            "USER_VIEW", "USER_MANAGE", "ROLE_MANAGE",
            "RESTAURANT_VIEW", "RESTAURANT_MANAGE",
            "TABLE_VIEW", "TABLE_MANAGE",
            "MENU_VIEW", "MENU_MANAGE",
            "ORDER_CREATE", "ORDER_MODIFY", "ORDER_MODIFY_OWN",
            "BILLING_MANAGE", "DISCOUNT_APPROVE",
            "KITCHEN_VIEW", "KITCHEN_UPDATE",
            "INVENTORY_VIEW", "INVENTORY_MANAGE",
            "REPORT_VIEW", "DASHBOARD_VIEW", "AUDIT_VIEW",
            "CUSTOMER_VIEW", "CUSTOMER_MANAGE",
            "DELIVERY_MANAGE",
            "AI_USE",
            // ---- Round 12: Purchase Order / Supplier / branch access ----
            "SUPPLIER_VIEW", "SUPPLIER_MANAGE",
            "PURCHASE_ORDER_VIEW", "PURCHASE_ORDER_CREATE", "PURCHASE_ORDER_APPROVE",
            "PURCHASE_ORDER_REJECT", "PURCHASE_ORDER_MODIFY",
            "PURCHASE_ORDER_RECEIVE", "PURCHASE_ORDER_CANCEL",
            "INVENTORY_ADJUST",
            "BRANCH_MANAGE",
            // ---- Round 13: AI Backbone Addendum Phase 1 (EOD / fraud rule engine / audit) ----
            "EOD_MANAGE", "EOD_OVERRIDE", "RULE_CONFIG_MANAGE",
            // ---- Round 15: Reservations screen (chefpay-web) ----
            "RESERVATION_VIEW", "RESERVATION_MANAGE",
            // ---- Phase 2: Organization/Branch/Terminal/Subscription management ----
            // BRANCH_MANAGE and ROLE_MANAGE already existed above (Round 12 / Phase 1) and are
            // reused as-is - see PHASE2_ORG_SUBSCRIPTION_DESIGN.md Section C's note on this.
            "ORGANIZATION_MANAGE", "TERMINAL_MANAGE", "SUBSCRIPTION_MANAGE", "SUBSCRIPTION_VIEW",
            // ---- Bistrodesk Phase 1: explicit central/cross-branch access (requirement #28) ----
            // See BranchAccessService's javadoc: this is what actually grants a user with specific
            // branches assigned the ability to see every branch, replacing the old *accidental*
            // "empty AppUser.branches = unrestricted" convention as the explicit mechanism going
            // forward (that convention still works unchanged for backward compatibility).
            "VIEW_ALL_BRANCHES"
    );

    private static final Map<String, List<String>> ROLE_PERMISSIONS = Map.of(
            // Phase 2: the new top role - full access, including everything ADMIN has, plus it is
            // the only role that can never be deactivated by another user (enforced in
            // UserController, not here - this map only decides which permissions a role holds).
            "OWNER", PERMISSION_CODES,
            "ADMIN", PERMISSION_CODES,
            "MANAGER", List.of("USER_VIEW", "RESTAURANT_VIEW", "RESTAURANT_MANAGE", "TABLE_VIEW",
                    "TABLE_MANAGE", "MENU_VIEW", "MENU_MANAGE", "DISCOUNT_APPROVE", "REPORT_VIEW",
                    "DASHBOARD_VIEW", "BILLING_MANAGE", "INVENTORY_VIEW", "INVENTORY_MANAGE", "AUDIT_VIEW",
                    "CUSTOMER_VIEW", "CUSTOMER_MANAGE", "DELIVERY_MANAGE", "AI_USE",
                    "SUPPLIER_VIEW", "SUPPLIER_MANAGE", "PURCHASE_ORDER_VIEW", "PURCHASE_ORDER_CREATE",
                    "PURCHASE_ORDER_APPROVE", "PURCHASE_ORDER_REJECT", "PURCHASE_ORDER_MODIFY",
                    "PURCHASE_ORDER_RECEIVE", "PURCHASE_ORDER_CANCEL", "INVENTORY_ADJUST", "BRANCH_MANAGE",
                    // Round 13: a Manager (Shift Supervisor-equivalent) can run the EOD wizard and tune
                    // fraud-rule thresholds, but does NOT get EOD_OVERRIDE - that Level-3/GM-equivalent
                    // step-up stays ADMIN-only (see RuleConfig/EodService's PIN step-up checks).
                    "EOD_MANAGE", "RULE_CONFIG_MANAGE", "RESERVATION_VIEW", "RESERVATION_MANAGE",
                    // Phase 2: a Manager runs the day-to-day Branches & Terminals screen (already had
                    // BRANCH_MANAGE) and can see the Subscription status/renewal screen, but does NOT
                    // get ORGANIZATION_MANAGE or SUBSCRIPTION_MANAGE - editing the org's own profile/tax
                    // details and initiating a plan change stay an Owner/Admin-tier action.
                    "TERMINAL_MANAGE", "SUBSCRIPTION_VIEW"),
            "CASHIER", List.of("TABLE_VIEW", "MENU_VIEW", "ORDER_CREATE", "ORDER_MODIFY", "BILLING_MANAGE",
                    "CUSTOMER_VIEW", "CUSTOMER_MANAGE", "PURCHASE_ORDER_VIEW", "PURCHASE_ORDER_CREATE",
                    "RESERVATION_VIEW", "RESERVATION_MANAGE"),
            "WAITER", List.of("TABLE_VIEW", "MENU_VIEW", "ORDER_CREATE", "ORDER_MODIFY_OWN",
                    "CUSTOMER_VIEW", "CUSTOMER_MANAGE", "RESERVATION_VIEW", "RESERVATION_MANAGE"),
            "KITCHEN", List.of("KITCHEN_VIEW", "KITCHEN_UPDATE", "MENU_VIEW", "INVENTORY_VIEW"),
            "VIEW_ONLY", List.of("REPORT_VIEW", "DASHBOARD_VIEW", "RESERVATION_VIEW")
    );

    /** Phase 2 item 24: the default {@link Feature} codes gate-able per {@link SubscriptionPlan} -
     * a starting catalogue seeded as data (same "an admin can regroup entitlements without a code
     * change" posture {@link Feature}'s own javadoc describes), not an exhaustive/final list - the
     * platform owner can add more via a future admin action without touching this class again;
     * this only guarantees every install has at least these to build its first plans around. */
    private static final List<String> FEATURE_CODES = List.of(
            "MULTI_BRANCH", "INVENTORY_MANAGEMENT", "PURCHASE_ORDERS", "ADVANCED_REPORTS",
            "AI_FEATURES", "RESERVATIONS", "DELIVERY_MANAGEMENT", "CUSTOM_BRANDING"
    );

    private final RestaurantRepository restaurantRepository;
    private final BranchRepository branchRepository;
    private final FloorRepository floorRepository;
    private final RestaurantTableRepository tableRepository;
    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final UserAccountService userAccountService;
    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final RuleConfigRepository ruleConfigRepository;
    private final AppUserRepository appUserRepository;
    private final DeviceRepository deviceRepository;
    private final FeatureRepository featureRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final TableSeedingService tableSeedingService;
    private final PlatformTransactionManager transactionManager;

    /** Hotfix (production outage, Round 5): every step below used to run inside ONE single
     * {@code @Transactional} method - a real production database turned out to have one menu item
     * referencing a since-deleted category (confirmed live via {@code
     * jakarta.persistence.EntityNotFoundException} on this exact step), and because everything
     * shared one transaction, that single bad row's step failing aborted the WHOLE method - meaning
     * the application could never finish starting up at all, on every single restart, until the
     * underlying data was fixed by hand. Every step now runs in its OWN transaction (via {@link
     * TransactionTemplate}, not {@code @Transactional} on each private method - a plain in-class
     * {@code this.foo()} call never actually goes through Spring's AOP proxy, so annotating these
     * private methods individually would silently do nothing) and a failing step's exception is
     * caught, logged clearly, and never blocks any of the others or crashes startup - a step that
     * threw simply didn't get to finish its own work this run and is safely retried (every step here
     * is already idempotent/only-ever-adds-what's-missing) on the next restart once whatever caused
     * it is fixed. */
    @Override
    public void run(ApplicationArguments args) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        runStep(tx, "ensurePermissionsAndRoles", this::ensurePermissionsAndRoles);
        runStep(tx, "ensureDemoRestaurantData", this::ensureDemoRestaurantData);
        runStep(tx, "ensureRuleConfigDefaults", this::ensureRuleConfigDefaults);
        runStep(tx, "ensurePhase2Backfill", this::ensurePhase2Backfill);
        runStep(tx, "ensureCustomerSupplierBranchBackfill", this::ensureCustomerSupplierBranchBackfill);
        runStep(tx, "ensureInventoryItemBranchBackfill", this::ensureInventoryItemBranchBackfill);
        runStep(tx, "ensureMenuItemBranchBackfill", this::ensureMenuItemBranchBackfill);
        runStep(tx, "ensureBranchProfileBackfill", this::ensureBranchProfileBackfill);
        runStep(tx, "ensureBranchTimezoneBackfill", this::ensureBranchTimezoneBackfill);
        runStep(tx, "ensureBranchDefaultTableSetupBackfill", this::ensureBranchDefaultTableSetupBackfill);
    }

    private void runStep(TransactionTemplate tx, String stepName, Runnable step) {
        try {
            tx.executeWithoutResult(status -> step.run());
        } catch (Exception ex) {
            log.error("ChefPay: startup data-seed step '{}' failed - continuing with every remaining "
                    + "step rather than blocking application startup entirely. This step's own work was "
                    + "rolled back and will be retried automatically on the next restart once whatever "
                    + "caused it is fixed (every step here only ever adds what's missing, so re-running "
                    + "it is always safe). Cause: {}", stepName, ex.toString(), ex);
        }
    }

    /** Bistrodesk follow-up requirement #5 (table creation/seeding fix): runs on every startup,
     * same "safe forever, only ever ADDS what's missing" discipline as every other backfill method
     * here - {@link TableSeedingService#ensureDefaultTableSetup} itself is the idempotency guard
     * (a branch that already has - or ever had - a table is untouched), so this just needs to call
     * it for every branch in the install. This is what self-heals every branch created before this
     * fix shipped (whether it got no floor at all, or only an empty one from the interim fix that
     * preceded this) without needing a one-off migration/SQL script. */
    private void ensureBranchDefaultTableSetupBackfill() {
        for (Branch branch : branchRepository.findAll()) {
            tableSeedingService.ensureDefaultTableSetup(branch);
        }
    }

    /** Round 13 (AI Backbone Addendum F1.5): same additive-on-every-startup discipline as {@link
     * #ensurePermissionsAndRoles()} - inserts the MVP default threshold for any {@link
     * FraudRuleCode} that doesn't have a {@link RuleConfig} row yet, and never touches one that
     * already exists (so a Level-3 user's edit via {@code RULE_CONFIG_MANAGE} survives a restart).
     * Defaults below are exactly the requirements doc's own "Rule Set (MVP)" table (Section 4,
     * F1.5) plus a 5% default for the F1.1 aggregator-mismatch check. */
    private void ensureRuleConfigDefaults() {
        record Default(FraudRuleCode code, BigDecimal threshold, BigDecimal secondaryThreshold, Integer windowMinutes,
                        AnomalySeverity severity, String description) {
        }
        List<Default> defaults = List.of(
                new Default(FraudRuleCode.POST_PRINT_VOID, new BigDecimal("90"), null, null, AnomalySeverity.HIGH,
                        "Bill printed, then an item is voided/cancelled (or the bill is discounted by at least N% of its subtotal), and a cash payment is still recorded on the same check."),
                new Default(FraudRuleCode.NO_SALE_FREQUENCY, new BigDecimal("3"), null, 60, AnomalySeverity.MEDIUM,
                        "More than N no-sale drawer opens by one cashier within a rolling window (minutes)."),
                new Default(FraudRuleCode.SPLIT_CHECK_CASH_EXTRACTION, BigDecimal.ONE, null, null, AnomalySeverity.HIGH,
                        "A cash-paid order has an item cancelled/voided after payment was already recorded."),
                new Default(FraudRuleCode.MANAGER_PIN_OVERUSE, new BigDecimal("15"), null, null, AnomalySeverity.CRITICAL,
                        "A Level-3 PIN authorizes more than N cash voids/overrides in one business date."),
                new Default(FraudRuleCode.EXCESSIVE_DISCOUNT, new BigDecimal("20"), null, null, AnomalySeverity.MEDIUM,
                        "A cashier's manual discounts for the business date exceed N% of their gross sales for that date."),
                new Default(FraudRuleCode.AGGREGATOR_SETTLEMENT_MISMATCH, new BigDecimal("5"), null, null, AnomalySeverity.HIGH,
                        "An aggregator's settled amount differs from the POS-recorded amount by more than N% for the business date."),
                new Default(FraudRuleCode.PEER_BASELINE_OUTLIER, new BigDecimal("2.00"), null, null, AnomalySeverity.MEDIUM,
                        "A cashier's void-to-sales ratio, cash-vs-card ratio, or discount % for a business date is more than N standard deviations from their same-role peer group's trailing 30-day average.")
        );

        int created = 0;
        for (Default d : defaults) {
            if (ruleConfigRepository.findByRuleCode(d.code()).isEmpty()) {
                ruleConfigRepository.save(RuleConfig.builder()
                        .ruleCode(d.code())
                        .thresholdValue(d.threshold())
                        .secondaryThresholdValue(d.secondaryThreshold())
                        .windowMinutes(d.windowMinutes())
                        .severity(d.severity())
                        .enabled(true)
                        .description(d.description())
                        .build());
                created++;
            }
        }
        if (created > 0) {
            log.info("ChefPay: seeded {} default fraud-rule threshold(s).", created);
        }
    }

    private void ensurePermissionsAndRoles() {
        Map<String, Permission> permissions = new java.util.HashMap<>();
        int created = 0;
        for (String code : PERMISSION_CODES) {
            Optional<Permission> existing = permissionRepository.findByCode(code);
            permissions.put(code, existing.orElseGet(
                    () -> permissionRepository.save(Permission.builder().code(code).description(code).build())));
            if (existing.isEmpty()) {
                created++;
            }
        }

        int rolesTouched = 0;
        for (Map.Entry<String, List<String>> entry : ROLE_PERMISSIONS.entrySet()) {
            Role role = roleRepository.findByNameIgnoreCase(entry.getKey())
                    .orElseGet(() -> Role.builder().name(entry.getKey()).description(entry.getKey() + " role")
                            .permissions(new HashSet<>()).build());

            boolean changed = false;
            for (String code : entry.getValue()) {
                if (role.getPermissions().add(permissions.get(code))) {
                    changed = true;
                }
            }
            if (changed || role.getId() == null) {
                roleRepository.save(role);
                rolesTouched++;
            }
        }

        if (created > 0 || rolesTouched > 0) {
            log.info("ChefPay: permission/role sync - {} permission(s) created, {} role(s) created or granted new permissions.",
                    created, rolesTouched);
        }
    }

    private void ensureDemoRestaurantData() {
        if (!restaurantRepository.findAll().isEmpty()) {
            log.info("ChefPay: existing restaurant data found, skipping demo data seed.");
            return;
        }
        log.info("ChefPay: empty database detected, seeding Phase 1 demo data...");

        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .name("My Restaurant")
                .currencySymbol("₹")
                .defaultTimezone("Asia/Kolkata")
                .build());
        Branch branch = branchRepository.save(Branch.builder().restaurant(restaurant).name("Main Branch").build());
        Floor floor = floorRepository.save(Floor.builder().branch(branch).name("Ground Floor").displayOrder(0).build());

        for (int i = 1; i <= 6; i++) {
            tableRepository.save(RestaurantTable.builder()
                    .floor(floor)
                    .name("T0" + i)
                    .seatingCapacity(i % 2 == 0 ? 4 : 2)
                    .status(TableStatus.AVAILABLE)
                    .gridRow((i - 1) / 3)
                    .gridColumn((i - 1) % 3)
                    .build());
        }

        MenuCategory beverages = menuCategoryRepository.save(MenuCategory.builder().name("Beverages").displayOrder(0).build());
        MenuCategory mains = menuCategoryRepository.save(MenuCategory.builder().name("Main Course").displayOrder(1).build());
        menuItemRepository.save(MenuItem.builder().category(beverages).name("Coke").price(new BigDecimal("60.00")).vegetarian(true).build());
        menuItemRepository.save(MenuItem.builder().category(mains).name("Paneer Tikka").price(new BigDecimal("280.00")).vegetarian(true).build());
        menuItemRepository.save(MenuItem.builder().category(mains).name("Chicken Biryani").price(new BigDecimal("250.00")).vegetarian(false).build());

        userAccountService.createUser("admin", "Administrator", "admin123", "1234", "ADMIN", "ADMIN001");

        inventoryItemRepository.save(InventoryItem.builder().name("Basmati Rice").unit("kg")
                .quantityOnHand(new BigDecimal("25.000")).reorderThreshold(new BigDecimal("5.000"))
                .costPerUnit(new BigDecimal("90.00")).build());
        inventoryItemRepository.save(InventoryItem.builder().name("Paneer").unit("kg")
                .quantityOnHand(new BigDecimal("8.000")).reorderThreshold(new BigDecimal("3.000"))
                .costPerUnit(new BigDecimal("320.00")).build());
        inventoryItemRepository.save(InventoryItem.builder().name("Chicken").unit("kg")
                .quantityOnHand(new BigDecimal("2.000")).reorderThreshold(new BigDecimal("4.000"))
                .costPerUnit(new BigDecimal("210.00")).build());
        inventoryItemRepository.save(InventoryItem.builder().name("Coke Bottles").unit("pcs")
                .quantityOnHand(new BigDecimal("48.000")).reorderThreshold(new BigDecimal("12.000"))
                .costPerUnit(new BigDecimal("35.00")).build());

        log.info("ChefPay: seed complete. Default login -> username='admin' password='admin123' (or PIN 1234). CHANGE THIS before real use.");
    }

    /**
     * Phase 2's critical difference from {@link #ensureDemoRestaurantData()}: that method skips
     * ENTIRELY the moment any {@code Restaurant} row exists, which is exactly true of every real,
     * already-running install (e.g. a live Oracle Cloud deployment) - so without a separate,
     * always-runs method, a real restaurant's database would never receive a single Phase 2 column
     * value or its default {@link Subscription} row, and the whole feature would appear completely
     * broken (no branch codes, no terminal numbers, no user codes, no subscription screen) on
     * exactly the installs that matter most, while looking perfect on a freshly wiped demo database.
     *
     * <p>Every check here is a plain "is this still unset" test per row, so running it on every
     * startup forever is safe: a value already backfilled is never touched again, and nothing here
     * ever overwrites a value an admin (or the platform owner) has since set deliberately.
     */
    private void ensurePhase2Backfill() {
        ensureDefaultFeaturesAndPlans();

        int branchCodesAssigned = 0;
        for (Branch branch : branchRepository.findAll()) {
            if (branch.getBranchCode() == null || branch.getBranchCode().isBlank()) {
                branch.setBranchCode(generateBranchCode());
                branchRepository.save(branch);
                branchCodesAssigned++;
                // Operator discoverability: a freshly-backfilled branch code otherwise lives only in
                // the database until someone opens the admin app's Branches page - and the POS
                // first-run flow's own single-branch auto-skip (GET /api/branches/default) means a
                // single-location install may never even be shown this code on screen. Logging it
                // plainly here at startup is the fix for "I have no organization or branch created,
                // how do I log in the first time?" when there actually is one, just undiscoverable.
                log.info("ChefPay: branch '{}' assigned code {} - use this to set up a POS terminal, "
                        + "or find/change it later under Branches & Terminals in the admin app.",
                        branch.getName(), branch.getBranchCode());
            }
        }

        int terminalsNumbered = 0;
        for (Branch branch : branchRepository.findAll()) {
            List<Device> unnumbered = deviceRepository.findAllByOrderByNameAsc().stream()
                    .filter(d -> branch.equals(d.getBranch()) && d.getSequenceNo() == null)
                    .toList();
            if (unnumbered.isEmpty()) {
                continue;
            }
            Integer highest = deviceRepository.findMaxSequenceNoForBranch(branch.getId());
            int next = (highest == null ? 0 : highest) + 1;
            for (Device device : unnumbered) {
                device.setSequenceNo(next++);
                deviceRepository.save(device);
                terminalsNumbered++;
            }
        }

        int userCodesAssigned = 0;
        for (AppUser user : appUserRepository.findByUserCodeIsNull()) {
            String roleName = user.getRole() == null ? "USER" : user.getRole().getName();
            user.setUserCode(userAccountService.generateUserCode(roleName));
            appUserRepository.save(user);
            userCodesAssigned++;
        }

        // Bistrodesk Phase 1: Subscription moved from per-restaurant to per-branch (see
        // Subscription.branch's javadoc). A pre-existing install's ORIGINAL Subscription row (the
        // one every real, already-running restaurant already has) must NOT be silently discarded in
        // favor of some arbitrary default plan/fresh dates - that row carries the platform owner's
        // ACTUAL assigned plan, start/expiry dates, and status, which is exactly the "must not be
        // silently defaulted onto a time-limited trial (that would start an unexpected expiry
        // countdown on a live paying business)" concern this backfill has always had, just now
        // applied to a per-branch move instead of a from-scratch create. Every legacy (pre-
        // Bistrodesk) row is identifiable as {@code branch == null} - only a pre-migration row can
        // ever have that, since every row created from this point on always sets a branch.
        //
        // The FIRST branch needing a subscription reuses the legacy row IN PLACE (same row id, same
        // audit history) - a single-branch install therefore ends this backfill with the exact same
        // one Subscription row it always had, just now pointed at its one Branch instead of its one
        // Restaurant: a complete no-op from that paying customer's point of view. Any ADDITIONAL
        // branch (a genuinely multi-branch install upgrading for the first time) gets a NEW row
        // cloned from that same legacy plan/dates/status, since before this change one shared
        // subscription implicitly covered the whole restaurant - each branch starts from an
        // identical copy of what was already being paid for, and the platform owner can then give
        // each branch its own distinct plan going forward via PlatformOwnerController. Only an
        // install with NO Subscription row at all yet (a genuinely brand-new database) falls back
        // to the original bootstrap logic: prefer the first ACTIVE, NON-TRIAL plan (by display
        // order) over whichever plan happens to be listed first, trial only if nothing paid is
        // configured.
        int subscriptionsCreated = 0;
        List<Branch> branchesNeedingSubscription = branchRepository.findAll().stream()
                .filter(b -> subscriptionRepository.findByBranchId(b.getId()).isEmpty())
                .toList();
        if (!branchesNeedingSubscription.isEmpty()) {
            Subscription legacy = subscriptionRepository.findAll().stream()
                    .filter(s -> s.getBranch() == null)
                    .findFirst().orElse(null);

            SubscriptionPlan clonePlan;
            LocalDate cloneStart;
            LocalDate cloneExpiry;
            SubscriptionStatus cloneStatus;
            int cloneGraceDays;
            String cloneWarningThresholds;
            String cloneSupportPhone;
            if (legacy != null) {
                clonePlan = legacy.getPlan();
                cloneStart = legacy.getStartDate();
                cloneExpiry = legacy.getExpiryDate();
                cloneStatus = legacy.getStatus();
                cloneGraceDays = legacy.getGracePeriodDays();
                cloneWarningThresholds = legacy.getWarningThresholdsDays();
                cloneSupportPhone = legacy.getSupportPhone();
            } else {
                List<SubscriptionPlan> activePlans = subscriptionPlanRepository.findByActiveTrueOrderByDisplayOrderAsc();
                clonePlan = activePlans.stream().filter(p -> !p.isTrial()).findFirst()
                        .or(() -> activePlans.stream().findFirst())
                        .orElse(null);
                cloneStart = LocalDate.now();
                cloneExpiry = clonePlan == null ? null : cloneStart.plusDays(clonePlan.getDurationDays());
                cloneStatus = SubscriptionStatus.ACTIVE;
                cloneGraceDays = 3;
                cloneWarningThresholds = "30,15,7,3,1";
                cloneSupportPhone = null;
            }

            if (clonePlan != null) {
                boolean firstBranch = true;
                for (Branch branch : branchesNeedingSubscription) {
                    if (firstBranch && legacy != null) {
                        legacy.setBranch(branch);
                        legacy.setRestaurant(branch.getRestaurant());
                        subscriptionRepository.save(legacy);
                    } else {
                        subscriptionRepository.save(Subscription.builder()
                                .branch(branch)
                                // @Deprecated legacy column, still NOT NULL in the schema - see
                                // Subscription.restaurant's javadoc for why this is still set.
                                .restaurant(branch.getRestaurant())
                                .plan(clonePlan)
                                .status(cloneStatus)
                                .startDate(cloneStart)
                                .expiryDate(cloneExpiry)
                                .gracePeriodDays(cloneGraceDays)
                                .warningThresholdsDays(cloneWarningThresholds)
                                .supportPhone(cloneSupportPhone)
                                .build());
                    }
                    firstBranch = false;
                    subscriptionsCreated++;
                }
            }
        }

        if (branchCodesAssigned > 0 || terminalsNumbered > 0 || userCodesAssigned > 0 || subscriptionsCreated > 0) {
            log.info("ChefPay: Phase 2 backfill - {} branch code(s), {} terminal number(s), {} user "
                            + "code(s) assigned; {} default subscription(s) created.",
                    branchCodesAssigned, terminalsNumbered, userCodesAssigned, subscriptionsCreated);
        }
    }

    /**
     * Bistrodesk branch-isolation release (requirement #6, user-confirmed decision: "strictly
     * mandatory branch, no shared option"). Unlike Inventory/Reservation/Menu/Tax/Discount - whose
     * branch column tolerates a permanent {@code null} ("shared/not-yet-assigned" is a legitimate
     * resting state for those, so nothing in {@code DataSeeder} backfills them) - Customer and
     * Supplier have no such resting state: every branchless row must be assigned a real branch, on
     * every startup, the same way {@link #ensurePhase2Backfill()}'s branch-code/user-code loops
     * keep re-checking for anything still unset. On Postgres/MySQL this is normally a no-op (V39
     * already backfilled every row at migration time); on SQLite (Flyway disabled, {@code ddl-auto
     * =update} only adds the nullable column with no data migration) this is the ONLY thing that
     * ever backfills a pre-existing row, so it must run here too. Same deterministic choice as V39:
     * the install's oldest branch by {@code createdAt}.
     */
    private void ensureCustomerSupplierBranchBackfill() {
        List<Branch> branches = branchRepository.findAll();
        if (branches.isEmpty()) {
            return;
        }
        Branch oldest = branches.stream().min(Comparator.comparing(Branch::getCreatedAt)).orElseThrow();

        int customersAssigned = 0;
        for (Customer customer : customerRepository.findAllByOrderByNameAsc()) {
            if (customer.getBranch() == null) {
                customer.setBranch(oldest);
                customerRepository.save(customer);
                customersAssigned++;
            }
        }

        int suppliersAssigned = 0;
        for (Supplier supplier : supplierRepository.findAll()) {
            if (supplier.getBranch() == null) {
                supplier.setBranch(oldest);
                supplierRepository.save(supplier);
                suppliersAssigned++;
            }
        }

        if (customersAssigned > 0 || suppliersAssigned > 0) {
            log.info("ChefPay: Bistrodesk branch-isolation backfill - {} customer(s) and {} supplier(s) "
                            + "assigned to branch '{}' (previously branchless).",
                    customersAssigned, suppliersAssigned, oldest.getName());
        }
    }

    /**
     * Bistrodesk branch-isolation release (requirement #3, user-confirmed decision: "strictly
     * branch specific, no shared option" - the same call as Customer/Supplier). Unlike
     * {@link #ensureCustomerSupplierBranchBackfill()}'s Postgres/MySQL counterpart (V39), inventory
     * items never had a dedicated backfill migration - Phase 2's {@code V34} deliberately left a
     * pre-existing multi-branch install's rows at {@code branch_id NULL} as a legitimate "shared/
     * not-yet-assigned" resting state, which this release now closes for good. Runs on every
     * startup (SQLite has no Flyway data migration at all; Postgres/MySQL never had one for this
     * table either) - same "only touch a still-null row" idempotent discipline as every other
     * backfill in this class, and the same deterministic choice as V39: the install's oldest branch
     * by {@code createdAt}. Also catches {@link #ensureDemoRestaurantData()}'s own branchless demo
     * items on a brand-new install, since both run in the same startup transaction.
     */
    private void ensureInventoryItemBranchBackfill() {
        List<Branch> branches = branchRepository.findAll();
        if (branches.isEmpty()) {
            return;
        }
        Branch oldest = branches.stream().min(Comparator.comparing(Branch::getCreatedAt)).orElseThrow();

        int itemsAssigned = 0;
        for (InventoryItem item : inventoryItemRepository.findAll()) {
            if (item.getBranch() == null) {
                item.setBranch(oldest);
                inventoryItemRepository.save(item);
                itemsAssigned++;
            }
        }

        if (itemsAssigned > 0) {
            log.info("ChefPay: Bistrodesk branch-isolation backfill - {} inventory item(s) assigned "
                            + "to branch '{}' (previously branchless).",
                    itemsAssigned, oldest.getName());
        }
    }

    /**
     * Bistrodesk branch-isolation release (requirement #1, user-confirmed decision: "strictly for
     * the branch where it is getting created, not shared with any other branch"). Unlike {@link
     * #ensureCustomerSupplierBranchBackfill()}'s Postgres/MySQL counterpart (V39), menu items never
     * had a dedicated backfill migration - Phase 3's {@code V36} deliberately left EVERY row at
     * {@code branch_id NULL} as the "shared/centralized, sold at every branch" resting state, which
     * this release now closes for good. Runs on every startup (SQLite has no Flyway data migration
     * at all) - same "only touch a still-null row" idempotent discipline as every other backfill in
     * this class, and the same deterministic choice as V39/V41: the install's oldest branch by
     * {@code createdAt}. Also catches {@link #ensureDemoRestaurantData()}'s own branchless demo
     * items (Coke, Paneer Tikka, Chicken Biryani) on a brand-new install, since both run in the same
     * startup transaction.
     */
    private void ensureMenuItemBranchBackfill() {
        List<Branch> branches = branchRepository.findAll();
        if (branches.isEmpty()) {
            return;
        }
        Branch oldest = branches.stream().min(Comparator.comparing(Branch::getCreatedAt)).orElseThrow();

        int itemsAssigned = 0;
        for (MenuItem item : menuItemRepository.findAll()) {
            if (item.getBranch() == null) {
                item.setBranch(oldest);
                menuItemRepository.save(item);
                itemsAssigned++;
            }
        }

        if (itemsAssigned > 0) {
            log.info("ChefPay: Bistrodesk branch-isolation backfill - {} menu item(s) assigned "
                            + "to branch '{}' (previously shared/centralized).",
                    itemsAssigned, oldest.getName());
        }
    }

    /**
     * Bistrodesk branch-isolation release (requirement #4, user-confirmed decision: "give each
     * branch its own restaurant profile"). On Postgres/MySQL {@code V40} already one-time-seeds
     * every branch's null profile fields from the legacy {@code Restaurant} row at migration time;
     * on SQLite (Flyway disabled, {@code ddl-auto=update} only adds the nullable columns) this is
     * the ONLY thing that ever seeds them, so it must run here too - same "only touch a still-null
     * field, never overwrite one an admin has since set" discipline as every other backfill in this
     * class. Only copies from the FIRST restaurant row (this schema has only ever had one).
     */
    private void ensureBranchProfileBackfill() {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        if (restaurant == null) {
            return;
        }
        int branchesSeeded = 0;
        for (Branch branch : branchRepository.findAll()) {
            boolean changed = false;
            if (branch.getGstin() == null && restaurant.getGstin() != null) {
                branch.setGstin(restaurant.getGstin());
                changed = true;
            }
            if (branch.getSupportPhone() == null && restaurant.getSupportPhone() != null) {
                branch.setSupportPhone(restaurant.getSupportPhone());
                changed = true;
            }
            if (branch.getReceiptFooterText() == null && restaurant.getReceiptFooterText() != null) {
                branch.setReceiptFooterText(restaurant.getReceiptFooterText());
                changed = true;
            }
            if (branch.getLogoImageBase64() == null && restaurant.getLogoImageBase64() != null) {
                branch.setLogoImageBase64(restaurant.getLogoImageBase64());
                changed = true;
            }
            if (changed) {
                branchRepository.save(branch);
                branchesSeeded++;
            }
        }
        if (branchesSeeded > 0) {
            log.info("ChefPay: Bistrodesk branch-profile backfill - seeded {} branch(es) with the "
                    + "restaurant's existing GSTIN/support phone/receipt footer/logo where previously unset.", branchesSeeded);
        }
    }

    /** Bistrodesk follow-up enhancement ("Local Time Zone During Branch Creation"): mirrors {@code
     * V44}'s Postgres/MySQL backfill for SQLite (Flyway disabled there, {@code ddl-auto=update} only
     * adds the nullable column with no data migration). Unlike the other branch-profile fields above
     * (gstin/support phone/etc, which tolerate staying permanently null), a branch's timezone is
     * used for real date/time computation ({@code ReportService#businessZone()}), so this always
     * ends with a real zone - falling back to "Asia/Kolkata" (matching {@code
     * Restaurant#defaultTimezone}'s own default) on the rare chance a branch's restaurant somehow has
     * a blank one too. Safe to run every startup: only ever fills a currently-null value. */
    private void ensureBranchTimezoneBackfill() {
        int branchesSeeded = 0;
        for (Branch branch : branchRepository.findAll()) {
            if (branch.getTimezone() != null && !branch.getTimezone().isBlank()) {
                continue;
            }
            String restaurantZone = branch.getRestaurant() == null ? null : branch.getRestaurant().getDefaultTimezone();
            branch.setTimezone(restaurantZone != null && !restaurantZone.isBlank() ? restaurantZone : "Asia/Kolkata");
            branchRepository.save(branch);
            branchesSeeded++;
        }
        if (branchesSeeded > 0) {
            log.info("ChefPay: Bistrodesk branch-timezone backfill - seeded {} branch(es) with a "
                    + "default time zone.", branchesSeeded);
        }
    }

    /** Additive seed, same discipline as {@link #ensurePermissionsAndRoles()}: only ever creates a
     * {@link Feature} code that's missing, and only ever creates the two starter {@link
     * SubscriptionPlan}s the FIRST time this runs against a database with none at all - once at
     * least one plan exists, this never creates/edits a plan again, so the platform owner's own
     * pricing/feature edits (via {@code PlatformOwnerController}) are never clobbered by a restart.
     * The two starter plans exist so {@link #ensurePhase2Backfill()} always has something to assign
     * a pre-Phase-2 install to, and so a brand-new install's Subscription screen is never empty. */
    private void ensureDefaultFeaturesAndPlans() {
        Map<String, Feature> features = new java.util.HashMap<>();
        int featuresCreated = 0;
        for (String code : FEATURE_CODES) {
            Optional<Feature> existing = featureRepository.findByCodeIgnoreCase(code);
            features.put(code, existing.orElseGet(
                    () -> featureRepository.save(Feature.builder().code(code).description(code).build())));
            if (existing.isEmpty()) {
                featuresCreated++;
            }
        }
        if (featuresCreated > 0) {
            log.info("ChefPay: seeded {} default feature(s).", featuresCreated);
        }

        if (!subscriptionPlanRepository.findAll().isEmpty()) {
            return;
        }
        SubscriptionPlan freeTrial = subscriptionPlanRepository.save(SubscriptionPlan.builder()
                .name("Free Trial")
                .description("Time-limited trial with a small set of core features - upgrade any time.")
                .durationDays(30)
                .price(BigDecimal.ZERO)
                .gstPercent(BigDecimal.ZERO)
                .maxBranches(1)
                .maxTerminals(2)
                .maxUsers(5)
                .trial(true)
                .active(true)
                .displayOrder(0)
                .build());
        SubscriptionPlan standard = subscriptionPlanRepository.save(SubscriptionPlan.builder()
                .name("Standard")
                .description("Full feature set, unlimited branches/terminals/users.")
                .durationDays(365)
                .price(new BigDecimal("999.00"))
                .gstPercent(new BigDecimal("18.00"))
                .maxBranches(null)
                .maxTerminals(null)
                .maxUsers(null)
                .trial(false)
                .active(true)
                .displayOrder(1)
                .features(new HashSet<>(features.values()))
                .build());
        log.info("ChefPay: seeded 2 default subscription plans ('{}', '{}') - edit pricing/features "
                + "via the platform-owner endpoint any time.", freeTrial.getName(), standard.getName());
    }

    /** Phase 2: a short, system-generated, numeric branch code (e.g. "1100") - see {@code
     * Branch#branchCode}'s javadoc for why this is generated rather than free-text. Mirrors {@code
     * UserAccountService#generateUserCode}'s "keep trying until an unused candidate is found"
     * shape, just with a 4-digit numeric alphabet instead of a role-prefixed one. */
    private String generateBranchCode() {
        SecureRandom random = new SecureRandom();
        for (int attempt = 0; attempt < 10_000; attempt++) {
            String candidate = String.format("%04d", random.nextInt(10_000));
            if (!branchRepository.existsByBranchCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique branch code.");
    }
}
