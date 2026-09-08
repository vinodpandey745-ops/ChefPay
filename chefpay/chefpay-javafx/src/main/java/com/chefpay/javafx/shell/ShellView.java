package com.chefpay.javafx.shell;

import com.chefpay.javafx.ai.AiToolsView;
import com.chefpay.javafx.areas.AreaManagementView;
import com.chefpay.javafx.audit.AuditLogView;
import com.chefpay.javafx.billing.BillingView;
import com.chefpay.javafx.billing.CashManagementView;
import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.ConnectionStatus;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.StompWebSocketClient;
import com.chefpay.javafx.client.SyncEngine;
import com.chefpay.javafx.client.dto.OrderDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.common.AppLogo;
import com.chefpay.javafx.common.ReceiptPrinter;
import com.chefpay.javafx.customers.CustomersView;
import com.chefpay.javafx.dashboard.DashboardView;
import com.chefpay.javafx.delivery.DeliveryBoysView;
import com.chefpay.javafx.duepayments.DuePaymentView;
import com.chefpay.javafx.eod.EodWizardView;
import com.chefpay.javafx.fraud.RuleConfigView;
import com.chefpay.javafx.intelligence.Round14IntelligenceView;
import com.chefpay.javafx.inventory.InventoryView;
import com.chefpay.javafx.kitchen.KitchenDisplayView;
import com.chefpay.javafx.kot.KotListingView;
import com.chefpay.javafx.menu.MenuManagementView;
import com.chefpay.javafx.notifications.NotificationListingView;
import com.chefpay.javafx.orders.OnlineOrdersView;
import com.chefpay.javafx.orders.OrderTakingView;
import com.chefpay.javafx.printers.PrinterProfileListingView;
import com.chefpay.javafx.purchasing.PurchaseOrdersView;
import com.chefpay.javafx.purchasing.SupplierManagementView;
import com.chefpay.javafx.reports.ReportsView;
import com.chefpay.javafx.roles.RoleManagementView;
import com.chefpay.javafx.settings.SettingsView;
import com.chefpay.javafx.specialnotes.SpecialNoteManagementView;
import com.chefpay.javafx.tables.TableManagementView;
import com.chefpay.javafx.tables.TableMatrixView;
import com.chefpay.javafx.users.UserManagementView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.format.DateTimeFormatter;

/**
 * Post-login shell: header (restaurant/user/clock/connection status/logout) + primary navigation.
 * Tables, Current Order, Kitchen, Billing, Inventory/Dashboard/Audit (Phase 5a - ARCHITECTURE.md
 * §12/§13), Settings/Reports (§14/§15) and now Customers/Cash Management (the "full-fledged POS"
 * round) are all real, live screens; a dedicated Reservations/Waitlist screen remains deferred
 * (table-level reservation is handled inline on the table matrix itself - see
 * {@code TableMatrixView}).
 */
public class ShellView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final StompWebSocketClient wsClient;
    private final Runnable onLogout;
    private final TableMatrixView tableMatrixView;
    private final KitchenDisplayView kitchenDisplayView;
    private final BillingView billingView;
    private final InventoryView inventoryView;
    private final DashboardView dashboardView;
    private final AuditLogView auditLogView;
    private final MenuManagementView menuManagementView;
    private final TableManagementView tableManagementView;
    private final SettingsView settingsView;
    private final ReportsView reportsView;
    private final CustomersView customersView;
    private final CashManagementView cashManagementView;
    private final OnlineOrdersView onlineOrdersView;
    // Round 8
    private final KotListingView kotListingView;
    private final NotificationListingView notificationListingView;
    private final DuePaymentView duePaymentView;
    private final PrinterProfileListingView printerProfileListingView;
    private final AreaManagementView areaManagementView;
    private final SpecialNoteManagementView specialNoteManagementView;
    // Round 9
    private final UserManagementView userManagementView;
    private final RoleManagementView roleManagementView;
    private final DeliveryBoysView deliveryBoysView;
    // Round 10
    private final AiToolsView aiToolsView;
    // Round 12 - Purchase Order / Supplier module (§12-§26)
    private final PurchaseOrdersView purchaseOrdersView;
    private final SupplierManagementView supplierManagementView;
    // Round 13 - AI Backbone Addendum EOD wizard + fraud rule config admin screen
    private final EodWizardView eodWizardView;
    private final RuleConfigView ruleConfigView;
    // Round 14 - Recipes/Costing, Menu Engineering, Seasonal Replenishment, Branch Report, Dynamic
    // Pricing, Rule Precision, Alert Delivery Log, and the NL Ops Assistant, all consolidated into
    // one tabbed "Intelligence & Insights" hub screen (see Round14IntelligenceView's own javadoc for
    // why these 8 sub-features were grouped into a single screen rather than 8 separate nav/hub
    // entries - it keeps buildOperationsHub from growing 8 more cards for one round's worth of work).
    private final Round14IntelligenceView round14IntelligenceView;
    /** The left nav column, built once. Round 9: hidden (via {@code root.setLeft(null)}) while an
     * order is open in {@link OrderTakingView} to give the order-taking screen the full width, the
     * same way the reference POS's own billing/order screen has no persistent side menu - restored
     * the moment the waiter backs out or jumps to billing. Kept as a field (rather than rebuilt
     * every time) so its internal state - which nav items are enabled, and whether the new
     * "Operations" group below is expanded or collapsed - survives being detached and reattached. */
    private VBox navBox;
    private final ObjectMapper mapper = new ObjectMapper();
    // Round 11 - restaurant CHAIN branch switcher (see SessionStore#currentBranchIdProperty's
    // javadoc). Hidden/empty until loadBranches() confirms the restaurant actually has 2+ branches -
    // a single-location restaurant (every deployment before this round) never sees this at all.
    private final javafx.scene.control.ChoiceBox<String> branchSwitcher = new javafx.scene.control.ChoiceBox<>();
    private final java.util.Map<String, java.util.UUID> branchIdByName = new java.util.HashMap<>();

    public ShellView(ApiClient apiClient, StompWebSocketClient wsClient, Runnable onLogout) {
        this.apiClient = apiClient;
        this.wsClient = wsClient;
        this.onLogout = onLogout;
        this.tableMatrixView = new TableMatrixView(apiClient, wsClient, this::openOrder);
        this.kitchenDisplayView = new KitchenDisplayView(apiClient, wsClient);
        this.billingView = new BillingView(apiClient, wsClient);
        this.inventoryView = new InventoryView(apiClient);
        this.dashboardView = new DashboardView(apiClient);
        this.auditLogView = new AuditLogView(apiClient);
        this.menuManagementView = new MenuManagementView(apiClient);
        this.tableManagementView = new TableManagementView(apiClient);
        this.settingsView = new SettingsView(apiClient);
        this.reportsView = new ReportsView(apiClient);
        this.customersView = new CustomersView(apiClient);
        this.cashManagementView = new CashManagementView(apiClient);
        this.onlineOrdersView = new OnlineOrdersView(apiClient, wsClient, this::openOrder);
        this.kotListingView = new KotListingView(apiClient);
        this.notificationListingView = new NotificationListingView(apiClient);
        this.duePaymentView = new DuePaymentView(apiClient);
        this.printerProfileListingView = new PrinterProfileListingView(apiClient);
        this.areaManagementView = new AreaManagementView(apiClient);
        this.specialNoteManagementView = new SpecialNoteManagementView(apiClient);
        this.userManagementView = new UserManagementView(apiClient);
        this.roleManagementView = new RoleManagementView(apiClient);
        this.deliveryBoysView = new DeliveryBoysView(apiClient);
        this.aiToolsView = new AiToolsView(apiClient);
        this.purchaseOrdersView = new PurchaseOrdersView(apiClient);
        this.supplierManagementView = new SupplierManagementView(apiClient);
        this.eodWizardView = new EodWizardView(apiClient);
        this.ruleConfigView = new RuleConfigView(apiClient);
        this.round14IntelligenceView = new Round14IntelligenceView(apiClient);

        root.setTop(buildHeader());
        this.navBox = buildNav();
        root.setLeft(navBox);
        if (isKitchenOnly()) {
            showKitchen();
        } else {
            showTables();
        }

        loadBranches();

        // Auto-print online-order KOTs (Restaurant.autoPrintOnlineOrders) - lives here rather than
        // on any one screen since it has to fire regardless of what the user currently has open
        // (unlike every other /topic/orders subscriber in this app, which only refreshes its own
        // screen). See onOrderCreatedEvent's javadoc for the full flow.
        wsClient.subscribe("/topic/orders", (dest, body) -> onOrderCreatedEvent(body));
    }

    /** Fires on every {@code /topic/orders} event; only ORDER_CREATED events for an
     * {@code ONLINE_ORDER} do anything, and only when {@code Restaurant.autoPrintOnlineOrders} is
     * on. Fetches both the order and the restaurant config fresh on each event (not cached) so a
     * Settings change takes effect on the very next order, not just after next login. Falls back to
     * the manual {@code ReceiptPrinter#show} dialog (rather than silently dropping the ticket) when
     * silent printing isn't possible - see {@code ReceiptPrinter#printSilently}'s javadoc for when
     * that happens (no printer configured, or the configured name doesn't match a real OS printer). */
    private void onOrderCreatedEvent(String rawJson) {
        JsonNode node;
        try {
            node = mapper.readTree(rawJson);
        } catch (Exception ignored) {
            return;
        }
        if (!"ORDER_CREATED".equals(node.path("eventType").asText(null))) {
            return;
        }
        String entityId = node.path("entityId").asText(null);
        if (entityId == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                var orderData = apiClient.get("/api/orders/" + entityId);
                OrderDtos.OrderDto order = apiClient.convert(orderData, OrderDtos.OrderDto.class);
                if (!"ONLINE_ORDER".equals(order.orderType())) {
                    return;
                }
                var restaurantData = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(restaurantData, RestaurantDtos.RestaurantDto.class);
                if (!restaurant.autoPrintOnlineOrders()) {
                    return;
                }
                String kot = ReceiptPrinter.buildKotText(order, restaurant.receiptPaperWidthChars());
                Platform.runLater(() -> {
                    boolean printed = ReceiptPrinter.printSilently(restaurant.receiptPrinterName(), kot);
                    if (!printed) {
                        ReceiptPrinter.show("KOT - " + order.orderNumber() + " (auto-print failed - printer not "
                                + "reachable, showing for manual print)", kot);
                    }
                });
            } catch (ApiException ignored) {
                // Non-fatal - the order still shows up on Online Orders/Kitchen either way; this
                // was purely a best-effort convenience print.
            }
        }, "chefpay-online-order-autoprint");
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 12 §3 reconciliation: this used to fetch EVERY branch on the whole restaurant from
     * {@code GET /api/restaurant} - before per-user branch access existed, that was the only list
     * there was. Now that login already resolves this user's own permission-filtered branch list
     * ({@code LoginResponse#effectiveBranches}, cached in {@link SessionStore#getEffectiveBranches()}
     * - see {@code AuthController}'s javadoc), reusing it here means a user restricted to a subset
     * of branches never sees this switcher offer one they're not allowed to work at, and no extra
     * network round trip is needed since the data is already in memory from login. Only shown at
     * all once this user's own effective list has 2+ branches - a single-branch install, or a user
     * restricted to exactly one branch, never sees it, same as before. Defaults to whichever branch
     * {@link ChefPayDesktopApp#resolveBranchThenShowShell} already resolved this session onto
     * (default branch / remembered terminal branch / explicit pick) rather than blindly resetting
     * to the alphabetically-first branch, which used to silently override that resolution the
     * moment this method ran. Only {@code TableMatrixView} is actually branch-scoped so far (see
     * {@code TableController}/{@code OrderController}'s optional {@code branchId} param) - Kitchen
     * Display and Billing still show every branch's data, a documented next step. */
    private void loadBranches() {
        java.util.List<com.chefpay.javafx.client.dto.LoginResult.BranchSummary> branches = SessionStore.get().getEffectiveBranches();
        if (branches.size() < 2) {
            return;
        }
        branchIdByName.clear();
        for (com.chefpay.javafx.client.dto.LoginResult.BranchSummary branch : branches) {
            branchIdByName.put(branch.name(), branch.id());
        }
        branchSwitcher.getItems().setAll(branchIdByName.keySet().stream().sorted().toList());
        java.util.UUID current = SessionStore.get().getCurrentBranchId();
        String currentName = branches.stream().filter(b -> b.id().equals(current))
                .map(com.chefpay.javafx.client.dto.LoginResult.BranchSummary::name).findFirst().orElse(null);
        if (currentName != null) {
            branchSwitcher.getSelectionModel().select(currentName);
        } else {
            branchSwitcher.getSelectionModel().selectFirst();
        }
        branchSwitcher.setVisible(true);
        branchSwitcher.setManaged(true);
    }

    private HBox buildHeader() {
        javafx.scene.Node logo = AppLogo.imageView(28);

        Label appName = new Label("Bistrodesk");
        appName.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: white;");

        HBox brand = new HBox(8, logo, appName);
        brand.setAlignment(Pos.CENTER_LEFT);

        Label userLabel = new Label();
        userLabel.setStyle("-fx-text-fill: white;");
        userLabel.textProperty().bind(SessionStore.get().displayNameProperty().concat("  •  ").concat(SessionStore.get().roleProperty()));

        Label clock = new Label();
        clock.setStyle("-fx-text-fill: white;");
        Timeline clockTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e ->
                clock.setText(java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("hh:mm:ss a")))));
        clockTimeline.setCycleCount(Animation.INDEFINITE);
        clockTimeline.play();

        Circle statusDot = new Circle(6, Color.GRAY);
        Label statusLabel = new Label("Connecting...");
        statusLabel.setStyle("-fx-text-fill: white;");
        wsClient.statusProperty().addListener((obs, old, status) -> updateStatus(statusDot, statusLabel, status));
        updateStatus(statusDot, statusLabel, wsClient.statusProperty().get());
        HBox statusBox = new HBox(6, statusDot, statusLabel);
        statusBox.setAlignment(Pos.CENTER);

        // Round 11: a second, separate chip for SyncEngine's local-cache state - distinct from the
        // WebSocket status above (that one reflects the live-updates connection; this one reflects
        // whether the HTTP API is reachable at all, and if not, how stale the offline cache is).
        // Both can legitimately disagree briefly (e.g. WebSocket still tearing down while a health
        // probe already recovered), which is fine - they're answering different questions.
        Label syncStatusLabel = new Label();
        syncStatusLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11px;");
        javafx.beans.binding.Bindings.createStringBinding(
                        () -> formatSyncStatus(SyncEngine.get().onlineProperty().get(), SyncEngine.get().lastSyncedAtProperty().get()),
                        SyncEngine.get().onlineProperty(), SyncEngine.get().lastSyncedAtProperty())
                .addListener((obs, old, text) -> syncStatusLabel.setText(text));
        syncStatusLabel.setText(formatSyncStatus(SyncEngine.get().onlineProperty().get(), SyncEngine.get().lastSyncedAtProperty().get()));

        // Round 11: hidden/empty until loadBranches() confirms 2+ branches exist - see its javadoc.
        branchSwitcher.setVisible(false);
        branchSwitcher.setManaged(false);
        branchSwitcher.setStyle("-fx-font-size: 12px;");
        branchSwitcher.getSelectionModel().selectedItemProperty().addListener((obs, old, name) -> {
            if (name == null) {
                return;
            }
            SessionStore.get().currentBranchIdProperty().set(branchIdByName.get(name));
            tableMatrixView.reload();
        });

        Button logoutButton = new Button("Logout");
        logoutButton.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-border-color: white; "
                + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand;");
        logoutButton.setOnAction(e -> onLogout.run());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(24, brand, spacer, branchSwitcher, statusBox, syncStatusLabel, userLabel, clock, logoutButton);
        header.setPadding(new Insets(12, 20, 12, 20));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #1b1f27;");
        return header;
    }

    /** Round 11: the sync-status chip's text - "Server unreachable - using cached data" is the
     * whole point of this feature being visible to staff at all: a waiter/cashier working through
     * an outage should be able to tell at a glance that they're on cached menu/tables, not silently
     * looking at data that might already be wrong. */
    private String formatSyncStatus(boolean online, java.time.LocalDateTime lastSyncedAt) {
        if (!online) {
            return "Server unreachable - using cached data";
        }
        if (lastSyncedAt == null) {
            return "";
        }
        return "Synced " + lastSyncedAt.format(DateTimeFormatter.ofPattern("hh:mm a"));
    }

    private void updateStatus(Circle dot, Label label, ConnectionStatus status) {
        switch (status) {
            case ONLINE -> {
                dot.setFill(Color.LIMEGREEN);
                label.setText("Online");
            }
            case CONNECTING -> {
                dot.setFill(Color.ORANGE);
                label.setText("Connecting...");
            }
            case OFFLINE -> {
                dot.setFill(Color.CRIMSON);
                label.setText("Offline - Local Mode");
            }
        }
    }

    /**
     * True for a login whose role only ever grants kitchen-facing permissions (the seeded
     * {@code KITCHEN} role: {@code KITCHEN_VIEW}/{@code KITCHEN_UPDATE} plus read-only
     * {@code MENU_VIEW}/{@code INVENTORY_VIEW}, nothing that touches tables/orders/billing) -
     * configurable entirely through Role management (whatever permission set an admin actually
     * grants a role decides this, no separate "kitchen mode" flag anywhere), matching the request
     * to make this "configurable as per client requirement" rather than a hardcoded role name
     * check. Such a login gets a restricted shell showing only the Kitchen Display, since every
     * other nav item here either 403s outright or shows an empty/useless screen for a user who
     * holds none of those permissions anyway - Tables/Billing/Online Orders are the three
     * exceptions that are unconditionally in the nav for everyone else (see the comment at their
     * addition below), so this restriction only bites for logins that would otherwise land on a
     * mostly-broken floor view with no way to actually do anything.
     */
    private boolean isKitchenOnly() {
        boolean hasKitchenAccess = SessionStore.get().hasPermission("KITCHEN_VIEW") || SessionStore.get().hasPermission("KITCHEN_UPDATE");
        boolean hasFloorOrBillingAccess = SessionStore.get().hasPermission("TABLE_VIEW")
                || SessionStore.get().hasPermission("ORDER_CREATE") || SessionStore.get().hasPermission("BILLING_MANAGE");
        return hasKitchenAccess && !hasFloorOrBillingAccess;
    }

    private VBox buildNav() {
        Label kitchen = navItem("Kitchen", null);
        kitchen.setOnMouseClicked(e -> showKitchen());

        if (isKitchenOnly()) {
            VBox kitchenOnlyNav = new VBox(4, kitchen);
            kitchenOnlyNav.setPadding(new Insets(16));
            kitchenOnlyNav.setPrefWidth(200);
            kitchenOnlyNav.setStyle("-fx-background-color: #f4f5f7;");
            return kitchenOnlyNav;
        }

        Label dashboard = navItem("Dashboard", null);
        dashboard.setOnMouseClicked(e -> showDashboard());

        Label tables = navItem("Tables", null);
        tables.setOnMouseClicked(e -> showTables());

        Label billing = navItem("Billing", null);
        billing.setOnMouseClicked(e -> showBilling());

        Label menu = navItem("Menu", null);
        menu.setOnMouseClicked(e -> showMenuManagement());

        Label reports = navItem("Reports", null);
        reports.setOnMouseClicked(e -> showReports());

        Label customers = navItem("Customers", null);
        customers.setOnMouseClicked(e -> showCustomers());

        Label onlineOrders = navItem("Online Orders", null);
        onlineOrders.setOnMouseClicked(e -> showOnlineOrders());

        Label settings = navItem("Settings", null);
        settings.setOnMouseClicked(e -> showSettings());

        // Round 8
        Label kot = navItem("KOT Listing", null);
        kot.setOnMouseClicked(e -> showKotListing());

        Label alerts = navItem("Alerts", null);
        alerts.setOnMouseClicked(e -> showNotifications());

        // Round 9 - single nav item opening the Operations hub screen (see buildOperationsHub's
        // javadoc for why this became a hub page instead of an in-sidebar expanding group).
        Label operations = navItem("Operations", null);
        operations.setOnMouseClicked(e -> showOperationsHub());

        VBox nav = new VBox(4);
        if (SessionStore.get().hasPermission("DASHBOARD_VIEW")) {
            nav.getChildren().add(dashboard);
        }
        nav.getChildren().addAll(tables, kitchen, billing, onlineOrders);
        // Catalog/floor setup - management-only (§7's "clients hide UI they can't use"), not
        // something regular waitstaff need in the nav (matches Inventory's VIEW/MANAGE split above,
        // except neither MenuController nor TableController grants a *_VIEW-only variant of these
        // write actions, so MANAGE is the only gate that makes sense here).
        if (SessionStore.get().hasPermission("MENU_MANAGE")) {
            nav.getChildren().add(menu);
        }
        // Round 8 - KOT Listing mirrors the Kitchen Display's own permission (KITCHEN_VIEW/
        // KITCHEN_UPDATE), same gate KotController itself requires, plus MANAGER for oversight.
        if (SessionStore.get().hasPermission("KITCHEN_VIEW") || SessionStore.get().hasPermission("KITCHEN_UPDATE")
                || SessionStore.get().hasPermission("MANAGER")) {
            nav.getChildren().add(kot);
        }
        // Alerts inbox - same gate NotificationController itself requires.
        if (SessionStore.get().hasPermission("DASHBOARD_VIEW") || SessionStore.get().hasPermission("MANAGER")) {
            nav.getChildren().add(alerts);
        }
        // REPORT_VIEW is already seeded onto MANAGER/VIEW_ONLY/ADMIN (DataSeeder) - same gate the
        // Dashboard nav item above uses one permission over, no new plumbing needed.
        if (SessionStore.get().hasPermission("REPORT_VIEW")) {
            nav.getChildren().add(reports);
        }
        // Guest directory - same VIEW/MANAGE split as Inventory above; CUSTOMER_VIEW is enough to
        // browse/search, CUSTOMER_MANAGE (gated inside the screen itself) to add/edit.
        if (SessionStore.get().hasPermission("CUSTOMER_VIEW") || SessionStore.get().hasPermission("CUSTOMER_MANAGE")) {
            nav.getChildren().add(customers);
        }
        // Settings edits the shared restaurant profile (currency, GSTIN, service charge, the
        // kitchen-sync toggle) - RESTAURANT_MANAGE is the same permission PUT /api/restaurant
        // already requires server-side, so this is consistent rather than a new looser gate.
        if (SessionStore.get().hasPermission("RESTAURANT_MANAGE")) {
            nav.getChildren().add(settings);
        }

        // Round 9 "Operations" - one nav item, gated on holding at least one of the permissions
        // any card on the hub screen needs (see buildOperationsHub) so a role with none of them
        // (e.g. WAITER) doesn't see an entry that opens an empty page.
        boolean hasAnyOperationsAccess =
                SessionStore.get().hasPermission("INVENTORY_VIEW") || SessionStore.get().hasPermission("INVENTORY_MANAGE")
                || SessionStore.get().hasPermission("AUDIT_VIEW")
                || SessionStore.get().hasPermission("TABLE_MANAGE")
                || SessionStore.get().hasPermission("BILLING_MANAGE") || SessionStore.get().hasPermission("MANAGER")
                || SessionStore.get().hasPermission("REPORT_VIEW")
                || SessionStore.get().hasPermission("RESTAURANT_MANAGE")
                || SessionStore.get().hasPermission("MENU_MANAGE")
                || SessionStore.get().hasPermission("USER_VIEW") || SessionStore.get().hasPermission("USER_MANAGE")
                || SessionStore.get().hasPermission("ROLE_MANAGE")
                || SessionStore.get().hasPermission("DELIVERY_MANAGE") || SessionStore.get().hasPermission("ORDER_MODIFY")
                || SessionStore.get().hasPermission("AI_USE")
                || SessionStore.get().hasPermission("PURCHASE_ORDER_VIEW") || SessionStore.get().hasPermission("PURCHASE_ORDER_CREATE")
                || SessionStore.get().hasPermission("PURCHASE_ORDER_APPROVE") || SessionStore.get().hasPermission("SUPPLIER_VIEW")
                || SessionStore.get().hasPermission("SUPPLIER_MANAGE")
                || SessionStore.get().hasPermission("EOD_MANAGE") || SessionStore.get().hasPermission("RULE_CONFIG_MANAGE")
                // Round 14 - Intelligence & Insights hub card's own gate (see buildOperationsHub).
                || SessionStore.get().hasPermission("REPORT_VIEW") || SessionStore.get().hasPermission("MENU_MANAGE");
        if (hasAnyOperationsAccess) {
            nav.getChildren().add(operations);
        }

        nav.setPadding(new Insets(16));
        nav.setPrefWidth(200);
        nav.setStyle("-fx-background-color: #f4f5f7;");
        return nav;
    }

    /** Round 9: back-office/admin/setup screens (Inventory, Cash Management, Users, etc.) shown as
     * a grid of cards on their own full-width screen, opened from the single "Operations" nav item
     * - reference-POS pattern (a "More"/"Operations" tile grid, e.g. Square's "More" tab) chosen
     * after an in-sidebar expanding group (this round's first attempt) turned out to just push the
     * rest of the sidebar down into a long scroll, which read poorly. Same permission gates as
     * before, just rendered as clickable cards instead of sidebar rows - nothing about who can see
     * what changed, only where it's found and how it's presented. Rebuilt fresh on every open
     * (cheap - a handful of Labels) so it never needs its own reload() plumbing. */
    private ScrollPane buildOperationsHub() {
        FlowPane grid = new FlowPane(16, 16);
        grid.setPadding(new Insets(24));

        if (SessionStore.get().hasPermission("INVENTORY_VIEW") || SessionStore.get().hasPermission("INVENTORY_MANAGE")) {
            grid.getChildren().add(opsCard("Inventory", "Stock levels, receive/deduct/waste, reorder thresholds.", this::showInventory));
        }
        if (SessionStore.get().hasPermission("AUDIT_VIEW")) {
            grid.getChildren().add(opsCard("Audit Log", "Every sensitive action, who did it and when.", this::showAuditLog));
        }
        if (SessionStore.get().hasPermission("TABLE_MANAGE")) {
            grid.getChildren().add(opsCard("Table Setup", "Floors, tables, seating capacity, layout.", this::showTableManagement));
        }
        if (SessionStore.get().hasPermission("BILLING_MANAGE") || SessionStore.get().hasPermission("MANAGER")
                || SessionStore.get().hasPermission("REPORT_VIEW")) {
            grid.getChildren().add(opsCard("Due Payments", "Outstanding customer dues across orders.", this::showDuePayments));
        }
        if (SessionStore.get().hasPermission("RESTAURANT_MANAGE")) {
            grid.getChildren().add(opsCard("Printer Setup", "Which printer handles bills, KOTs, receipts.", this::showPrinterProfiles));
        }
        if (SessionStore.get().hasPermission("TABLE_MANAGE")) {
            grid.getChildren().add(opsCard("Areas", "Seating-section presets (Indoor, Patio, AC Hall...).", this::showAreaManagement));
        }
        if (SessionStore.get().hasPermission("MENU_MANAGE")) {
            grid.getChildren().add(opsCard("Special Notes", "Quick-pick cooking instructions for order-taking.", this::showSpecialNoteManagement));
        }
        if (SessionStore.get().hasPermission("BILLING_MANAGE")) {
            grid.getChildren().add(opsCard("Cash Management", "Cash drawer ledger and Day End reconciliation.", this::showCashManagement));
        }
        if (SessionStore.get().hasPermission("USER_VIEW") || SessionStore.get().hasPermission("USER_MANAGE")) {
            grid.getChildren().add(opsCard("Users", "Staff accounts, roles, password/PIN resets.", this::showUserManagement));
        }
        if (SessionStore.get().hasPermission("ROLE_MANAGE")) {
            grid.getChildren().add(opsCard("Roles & Permissions", "What each role (Admin/Manager/Cashier...) can do.", this::showRoleManagement));
        }
        if (SessionStore.get().hasPermission("DELIVERY_MANAGE") || SessionStore.get().hasPermission("ORDER_MODIFY")) {
            grid.getChildren().add(opsCard("Delivery Boys", "Delivery roster and who's assigned to what.", this::showDeliveryBoys));
        }
        // Round 10 - AI Menu Import, Ask Your Data, Reorder Drafts, Anomaly Scan, Nightly Summary.
        // AI_USE alone gates visibility here; each individual feature still separately requires the
        // master AI Features switch, a saved key, and its own switch to be on in Settings.
        if (SessionStore.get().hasPermission("AI_USE")) {
            grid.getChildren().add(opsCard("AI Tools", "Menu import from a photo, Ask Your Data, reorder drafts, anomaly scan.", this::showAiTools));
        }
        // Round 12 §12-§26 - Purchase Order module. VIEW/CREATE/APPROVE all see the list (the
        // "dedicated Manager PO Approval" actions are permission-gated buttons inside that same
        // screen, not a second card here - see PurchaseOrdersView's own javadoc for why).
        if (SessionStore.get().hasPermission("PURCHASE_ORDER_VIEW") || SessionStore.get().hasPermission("PURCHASE_ORDER_CREATE")
                || SessionStore.get().hasPermission("PURCHASE_ORDER_APPROVE")) {
            grid.getChildren().add(opsCard("Purchase Orders", "Create, approve, send to supplier, and receive stock.", this::showPurchaseOrders));
        }
        if (SessionStore.get().hasPermission("SUPPLIER_VIEW") || SessionStore.get().hasPermission("SUPPLIER_MANAGE")) {
            grid.getChildren().add(opsCard("Suppliers", "Supplier directory for purchase orders.", this::showSuppliers));
        }
        // Round 13 (AI Backbone Addendum F1.1-F1.7) - the guided EOD wizard and its rule-threshold
        // admin screen.
        if (SessionStore.get().hasPermission("EOD_MANAGE")) {
            grid.getChildren().add(opsCard("End of Day", "Channel ingestion, blind cash count, fraud review, Z-Report.", this::showEodWizard));
        }
        if (SessionStore.get().hasPermission("RULE_CONFIG_MANAGE")) {
            grid.getChildren().add(opsCard("Fraud Rule Config", "Thresholds for the Tier-1 loss-prevention rule engine.", this::showRuleConfig));
        }
        // Round 14 - Recipes/costing, seasonal replenishment + auto-PO drafts, menu engineering
        // matrix, consolidated branch report, dynamic pricing suggestions, fraud rule precision,
        // alert delivery log, and the NL ops assistant - one card opening the tabbed hub. Gated on
        // REPORT_VIEW (every MANAGER/ADMIN/VIEW_ONLY role already holds it, and every Round 14
        // endpoint this screen calls is itself gated on a permission at least that broad) rather than
        // inventing a new Round-14-specific permission, matching DataSeeder's Round 14 conclusion
        // that no new permission codes were needed this round.
        if (SessionStore.get().hasPermission("REPORT_VIEW") || SessionStore.get().hasPermission("MENU_MANAGE")) {
            grid.getChildren().add(opsCard("Intelligence & Insights", "Recipes/costing, menu engineering, dynamic pricing, "
                    + "seasonal replenishment, rule precision, alerts, and the AI ops assistant.", this::showRound14Intelligence));
        }

        VBox content = new VBox(grid);
        Label title = new Label("Operations");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox header = new HBox(title);
        header.setPadding(new Insets(16, 24, 0, 24));
        content.getChildren().add(0, header);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    /** One clickable card on the Operations hub - title, one-line description, hover pop matching
     * {@link #navItem}'s same affordance. */
    private VBox opsCard(String title, String description, Runnable action) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1b1f27;");
        Label descLabel = new Label(description);
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        VBox card = new VBox(6, titleLabel, descLabel);
        card.setPrefSize(220, 110);
        card.setMaxSize(220, 110);
        card.setPadding(new Insets(14));
        String baseStyle = "-fx-background-color: white; -fx-border-color: #ddd; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-cursor: hand;";
        card.setStyle(baseStyle);
        card.setOnMouseEntered(e -> card.setStyle(baseStyle + " -fx-border-color: #2c3e50; -fx-translate-y: -2;"));
        card.setOnMouseExited(e -> card.setStyle(baseStyle));
        card.setOnMouseClicked(e -> action.run());
        return card;
    }

    private void showOperationsHub() {
        kitchenDisplayView.stop();
        root.setCenter(buildOperationsHub());
    }

    /** Round 9: a small hover "pop" (slight right-shift + a light highlight fill) on every
     * clickable nav item, purely a visual affordance that this is clickable - mirrors how most
     * desktop app side-menus give a hover cue. Disabled (badge != null) items deliberately get no
     * hover effect, matching their already-non-interactive styling below. */
    private Label navItem(String text, String badge) {
        Label label = new Label(badge == null ? text : text + "   (" + badge + ")");
        label.setPrefHeight(44);
        label.setMaxWidth(Double.MAX_VALUE);
        boolean enabled = badge == null;
        String baseStyle = "-fx-font-size: 14px; -fx-padding: 0 0 0 12; -fx-background-radius: 6; -fx-text-fill: "
                + (enabled ? "#1b1f27; -fx-font-weight: bold; -fx-cursor: hand;" : "#8a8f98;");
        label.setStyle(baseStyle);
        if (enabled) {
            label.setOnMouseEntered(e -> label.setStyle(baseStyle
                    + " -fx-background-color: #e8eaee; -fx-translate-x: 4;"));
            label.setOnMouseExited(e -> label.setStyle(baseStyle));
        }
        return label;
    }

    private void showTables() {
        kitchenDisplayView.stop();
        tableMatrixView.reload();
        root.setCenter(tableMatrixView.view());
    }

    private void showKitchen() {
        root.setCenter(kitchenDisplayView.view());
        kitchenDisplayView.start();
    }

    private void showBilling() {
        kitchenDisplayView.stop();
        root.setCenter(billingView.view());
        billingView.reload();
    }

    private void showInventory() {
        kitchenDisplayView.stop();
        root.setCenter(inventoryView.view());
        inventoryView.reload();
    }

    private void showPurchaseOrders() {
        kitchenDisplayView.stop();
        root.setCenter(purchaseOrdersView.view());
        purchaseOrdersView.reload();
    }

    private void showSuppliers() {
        kitchenDisplayView.stop();
        root.setCenter(supplierManagementView.view());
        supplierManagementView.reload();
    }

    private void showEodWizard() {
        kitchenDisplayView.stop();
        root.setCenter(eodWizardView.view());
    }

    private void showRuleConfig() {
        kitchenDisplayView.stop();
        root.setCenter(ruleConfigView.view());
        ruleConfigView.reload();
    }

    // Round 14
    private void showRound14Intelligence() {
        kitchenDisplayView.stop();
        root.setCenter(round14IntelligenceView.view());
        round14IntelligenceView.reload();
    }

    private void showDashboard() {
        kitchenDisplayView.stop();
        root.setCenter(dashboardView.view());
        dashboardView.reload();
    }

    private void showAuditLog() {
        kitchenDisplayView.stop();
        root.setCenter(auditLogView.view());
        auditLogView.reload();
    }

    private void showMenuManagement() {
        kitchenDisplayView.stop();
        root.setCenter(menuManagementView.view());
        menuManagementView.reload();
    }

    private void showTableManagement() {
        kitchenDisplayView.stop();
        root.setCenter(tableManagementView.view());
        tableManagementView.reload();
    }

    private void showSettings() {
        kitchenDisplayView.stop();
        root.setCenter(settingsView.view());
        settingsView.reload();
    }

    private void showReports() {
        kitchenDisplayView.stop();
        root.setCenter(reportsView.view());
        reportsView.reload();
    }

    private void showCustomers() {
        kitchenDisplayView.stop();
        root.setCenter(customersView.view());
        customersView.reload();
    }

    private void showCashManagement() {
        kitchenDisplayView.stop();
        root.setCenter(cashManagementView.view());
        cashManagementView.reload();
    }

    private void showOnlineOrders() {
        kitchenDisplayView.stop();
        root.setCenter(onlineOrdersView.view());
        onlineOrdersView.reload();
    }

    private void showKotListing() {
        kitchenDisplayView.stop();
        root.setCenter(kotListingView.view());
        kotListingView.reload();
    }

    private void showNotifications() {
        kitchenDisplayView.stop();
        root.setCenter(notificationListingView.view());
        notificationListingView.reload();
    }

    private void showDuePayments() {
        kitchenDisplayView.stop();
        root.setCenter(duePaymentView.view());
        duePaymentView.reload();
    }

    private void showPrinterProfiles() {
        kitchenDisplayView.stop();
        root.setCenter(printerProfileListingView.view());
        printerProfileListingView.reload();
    }

    private void showAreaManagement() {
        kitchenDisplayView.stop();
        root.setCenter(areaManagementView.view());
        areaManagementView.reload();
    }

    private void showSpecialNoteManagement() {
        kitchenDisplayView.stop();
        root.setCenter(specialNoteManagementView.view());
        specialNoteManagementView.reload();
    }

    // Round 9
    private void showUserManagement() {
        kitchenDisplayView.stop();
        root.setCenter(userManagementView.view());
        userManagementView.reload();
    }

    private void showRoleManagement() {
        kitchenDisplayView.stop();
        root.setCenter(roleManagementView.view());
        roleManagementView.reload();
    }

    private void showDeliveryBoys() {
        kitchenDisplayView.stop();
        root.setCenter(deliveryBoysView.view());
        deliveryBoysView.reload();
    }

    // Round 10
    private void showAiTools() {
        kitchenDisplayView.stop();
        root.setCenter(aiToolsView.view());
        aiToolsView.reload();
    }

    /** Round 9: reached from {@code OrderTakingView}'s new "Bill Table" bottom-bar button - jumps
     * straight into Billing already focused on this one order (see {@code BillingView#focusOrder}),
     * instead of leaving the cashier to open Billing from the nav and pick the table back out of
     * its list themselves. The nav is restored first (see {@link #openOrder}'s javadoc for why it
     * was hidden), same as backing out normally. */
    private void showBillingForOrder(com.chefpay.javafx.client.dto.OrderDtos.OrderDto order) {
        kitchenDisplayView.stop();
        root.setCenter(billingView.view());
        billingView.focusOrder(order.id());
    }

    /** Round 9: the left nav is hidden for the duration of order-taking (matches the reference
     * POS's own full-width billing/order screen, and the request to "hide this circled side menu
     * when in the table screen taking order") - {@link #navBox} is simply detached from {@code
     * root.setLeft}, not rebuilt, so its Operations-group expanded/collapsed state is unaffected.
     * It's reattached the moment the waiter backs out to Tables or jumps straight to billing. */
    private void openOrder(com.chefpay.javafx.client.dto.OrderDtos.OrderDto order) {
        kitchenDisplayView.stop();
        root.setLeft(null);
        OrderTakingView orderView = new OrderTakingView(apiClient, wsClient, order,
                () -> { root.setLeft(navBox); showTables(); },
                o -> { root.setLeft(navBox); showBillingForOrder(o); });
        root.setCenter(orderView.view());
    }

    public Parent view() {
        return root;
    }
}
