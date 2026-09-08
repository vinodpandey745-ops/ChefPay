package com.chefpay.javafx.orders;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.StompWebSocketClient;
import com.chefpay.javafx.client.dto.MenuDtos;
import com.chefpay.javafx.client.dto.OrderDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.client.dto.SpecialNoteDtos;
import com.chefpay.javafx.common.ReceiptPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Order-taking / running-order screen (requirement §41/§42). Every mutation posts straight to
 * chefpay-server and re-renders from its response - there is no client-side price/total math
 * here (§50/§61: money and business rules live on the backend only). Any WebSocket event that
 * names this order's id triggers a refetch, so if another terminal adds/removes an item this
 * screen updates itself within about a second without the user doing anything (requirement §2/§62).
 */
public class OrderTakingView {

    /** Order-level lifecycle steps a waiter walks through from the floor, in order (requirement
     * §13's state machine only allows one step at a time - {@code markOrderServed()} below chains
     * through whichever of these come after the order's current status). BILL_REQUESTED and later
     * are cashier/BillingView territory (§4's "Request Bill" already lives there), not this screen. */
    private static final List<String> ORDER_PROGRESS_CHAIN = List.of(
            "SENT_TO_KITCHEN", "ACCEPTED", "PREPARING", "READY", "SERVED");

    /** Round 9: statuses at which this order can actually be billed - mirrors {@code
     * BillingView#BILLABLE_STATUSES} exactly (kept as a separate copy rather than a shared
     * constant since these two classes don't otherwise share any code, matching this codebase's
     * existing pattern of small, independently-verifiable classes over cross-module coupling for
     * a 4-string set). Gates the new bottom-bar "Bill Table" quick action below. */
    private static final Set<String> BILLABLE_STATUSES = Set.of("SERVED", "BILL_REQUESTED", "BILLED", "PAYMENT_PENDING");

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final StompWebSocketClient wsClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Runnable onBack;
    /** Round 9: "Bill Table" bottom-bar action - jumps straight into {@code BillingView} already
     * focused on this order, instead of making the cashier open Billing from the nav and find this
     * table again in its order list. Multi-table billing still goes through the regular Billing nav
     * item unchanged - this is purely a shortcut for the common "settle just this one table" case. */
    private final Consumer<OrderDtos.OrderDto> onBillNow;

    // ---- Round 9 bottom action bar (mirrors the reference POS's Save / Save & Print / KOT /
    // KOT & Print / Bill row) - kept as instance fields so renderOrder can enable/disable each one
    // based on current order state, the same way updatePrimaryAction already owns primaryActionButton. ----
    private final Button saveAndPrintButton = new Button("Save & Print");
    private final Button eBillButton = new Button("Save & EBill");
    private final Button kotButton = new Button("KOT");
    private final Button kotAndPrintButton = new Button("KOT & Print");
    private final Button billTableButton = new Button("Bill Table");

    private final Label headerLabel = new Label();
    private final Label statusLabel = new Label();
    private final VBox itemsBox = new VBox(8);
    private final Label totalLabel = new Label();
    private final FlowPane menuGrid = new FlowPane(10, 10);
    private final Button primaryActionButton = new Button();
    /** Round 11 - "when physically verified then can directly bill (without kitchen interference)".
     * Only ever shown alongside {@link #primaryActionButton} while it's offering "Send to Kitchen"
     * (i.e. status is still PLACED) and {@code Restaurant#kotOptionalEnabled} is on - see {@link
     * #billDirectlySkippingKitchen}. */
    private final Button billDirectlyButton = new Button("Bill Directly (Skip Kitchen)");

    /** Round 8: persistent category nav column to the left of {@link #menuGrid} (replaces the old
     * tile-drill-down where tapping a category swapped the whole grid and a "< Categories" tile
     * brought you back). One button per active category, always visible, selected one highlighted -
     * clicking a different category just re-filters {@link #menuGrid}, nothing to "go back" from. */
    private final VBox categorySidebar = new VBox(4);

    /** Round 8 cart-panel header (task: richer "inside table" header) - order type, visible item
     * count, and (once at least one item has been sent at least once) the most recent KOT number
     * and a reprint button. Wired into {@link #renderOrder} exactly like headerLabel/statusLabel. */
    private final Label orderTypeLabel = new Label();
    private final Label itemCountLabel = new Label();
    private final Label kotBadgeLabel = new Label();
    private final Button printKotButton = new Button("Print KOT");

    /** Cached last menu payload and which category is currently selected in {@link #categorySidebar}
     * (always one, once the menu has loaded at least once) - filters {@link #menuGrid} to that
     * category's items. Categories come pre-ordered by {@code displayOrder} from the server (same
     * order Menu Management already lets an admin configure), so no client-side sorting needed. */
    private List<MenuDtos.CategoryDto> loadedCategories = List.of();
    private MenuDtos.CategoryDto selectedCategory;

    private OrderDtos.OrderDto currentOrder;

    /** Whether "Mark Order Served" must wait for the kitchen to actually serve every item first -
     * mirrors {@code Restaurant#requireKitchenSyncForServed}, loaded from the server on open.
     * Defaults to the entity's own default (synced/strict) so a slow config fetch never briefly
     * offers the looser behavior before the real value arrives. */
    private boolean requireKitchenSyncForServed = true;

    /** Round 11: the Settings-configured silent-print printer (same {@code Restaurant
     * #receiptPrinterName} {@code BillingView} uses), loaded alongside {@link
     * #requireKitchenSyncForServed} above. Null/blank (the default until the config fetch lands,
     * or if nothing is configured) means {@link #printKot} falls back to today's "show()" preview
     * dialog rather than ever silently failing to produce a ticket. */
    private String receiptPrinterName;

    /** Round 11: whether {@link #billDirectlyButton} is offered at all - mirrors {@link
     * #requireKitchenSyncForServed}'s loading pattern. The real enforcement lives server-side
     * ({@code OrderService#updateOrderStatus}'s gate) regardless of what this client-side flag
     * says; this only controls whether the button is shown. */
    private boolean kotOptionalEnabled = false;

    /**
     * True while an add/remove/quantity-change/send-to-kitchen/mark-served request is in flight.
     * Every mutation method below checks this at entry and no-ops if it's already true, and the
     * menu/cart panels are visually disabled for the (usually well under a second) round trip.
     * This exists specifically to close a race that used to be reachable by tapping the same menu
     * tile twice quickly: both taps read {@code currentOrder.version()} before either response
     * came back, so the second request's optimistic-lock check failed against the version the
     * first request had already bumped - surfacing as "This order was updated by another
     * terminal" even though it was the same terminal. Under real load the same double-fire could
     * also trip SQLite's single-writer file lock (reported as a raw SQLITE_BUSY 500). Locking the
     * UI for the round trip makes both failure modes structurally impossible from this screen
     * without making the screen feel sluggish - see also the quantity +/- controls in
     * {@link #buildItemRow}, which give a faster way to add several of the same item than tapping
     * the tile repeatedly in the first place. */
    private boolean mutationInFlight = false;

    public OrderTakingView(ApiClient apiClient, StompWebSocketClient wsClient, OrderDtos.OrderDto initialOrder, Runnable onBack,
                            Consumer<OrderDtos.OrderDto> onBillNow) {
        this.apiClient = apiClient;
        this.wsClient = wsClient;
        this.onBack = onBack;
        this.onBillNow = onBillNow;
        this.currentOrder = initialOrder;

        root.setTop(buildHeader());
        root.setLeft(buildMenuPanel());
        root.setCenter(buildOrderPanel());
        root.setBottom(buildBottomActionBar());

        wsClient.subscribe("/topic/orders", (dest, body) -> onOrderEvent(body));

        renderOrder(initialOrder);
        loadMenu();
        loadRestaurantConfig();
    }

    /** Fetches whether this restaurant requires kitchen sync before a waiter can mark an order
     * served (Settings screen's toggle). A failed fetch just keeps the safer default above rather
     * than blocking order-taking on it. */
    private void loadRestaurantConfig() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                com.chefpay.javafx.client.LocalDatabase.putCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_RESTAURANT, data.toString());
                requireKitchenSyncForServed = restaurant.requireKitchenSyncForServed();
                receiptPrinterName = restaurant.receiptPrinterName();
                kotOptionalEnabled = restaurant.kotOptionalEnabled();
                Platform.runLater(() -> updatePrimaryAction(currentOrder));
            } catch (ApiException ignored) {
                // Round 11: a live fetch failing (offline, or just after a fresh app start with no
                // connection yet) falls back to whatever SyncEngine last cached, rather than always
                // running on the hardcoded safe default even when a perfectly good cached value exists.
                com.chefpay.javafx.client.LocalDatabase.CachedPayload cached =
                        com.chefpay.javafx.client.LocalDatabase.readCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_RESTAURANT);
                if (cached != null) {
                    try {
                        RestaurantDtos.RestaurantDto restaurant = apiClient.convert(apiClient.parseCached(cached.payloadJson()), RestaurantDtos.RestaurantDto.class);
                        requireKitchenSyncForServed = restaurant.requireKitchenSyncForServed();
                        receiptPrinterName = restaurant.receiptPrinterName();
                        kotOptionalEnabled = restaurant.kotOptionalEnabled();
                        Platform.runLater(() -> updatePrimaryAction(currentOrder));
                    } catch (RuntimeException parseEx) {
                        // Corrupt/unparseable cache entry - keep the safer hardcoded default.
                    }
                }
            }
        }, "chefpay-restaurant-config-load");
        worker.setDaemon(true);
        worker.start();
    }

    private HBox buildHeader() {
        Button back = new Button("< Tables");
        back.setOnAction(e -> onBack.run());

        headerLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        VBox titleBox = new VBox(2, headerLabel, statusLabel);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        billDirectlyButton.setStyle("-fx-background-color: #7f8c8d; -fx-text-fill: white; -fx-font-weight: bold;");
        billDirectlyButton.setOnAction(e -> billDirectlySkippingKitchen());
        billDirectlyButton.setVisible(false);
        billDirectlyButton.setManaged(false);

        HBox header = new HBox(16, back, titleBox, spacer, billDirectlyButton, primaryActionButton);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    /** Round 8: returns the persistent-sidebar + item-grid layout described on
     * {@link #categorySidebar}'s javadoc, in place of the old single-{@code ScrollPane} tile
     * drill-down. Still just an internal region handed to {@code root.setLeft(...)} - the overall
     * screen layout (menu on the left, order/cart in the center) is unchanged. */
    private Region buildMenuPanel() {
        categorySidebar.setPadding(new Insets(12, 6, 12, 6));
        categorySidebar.setStyle("-fx-background-color: #2c3e50;");
        ScrollPane sidebarScroll = new ScrollPane(categorySidebar);
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setPrefWidth(170);
        sidebarScroll.setMinWidth(170);
        sidebarScroll.setMaxWidth(170);
        sidebarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.setStyle("-fx-background-color: #2c3e50; -fx-background: #2c3e50;");

        menuGrid.setPadding(new Insets(16));
        ScrollPane itemsScroll = new ScrollPane(menuGrid);
        itemsScroll.setFitToWidth(true);
        HBox.setHgrow(itemsScroll, Priority.ALWAYS);

        HBox panel = new HBox(sidebarScroll, itemsScroll);
        panel.setPrefWidth(650);
        return panel;
    }

    private ScrollPane buildOrderPanel() {
        HBox cartHeader = buildCartHeader();

        itemsBox.setPadding(new Insets(16));
        ScrollPane itemsScroll = new ScrollPane(itemsBox);
        itemsScroll.setFitToWidth(true);

        totalLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox totalRow = new HBox(totalLabel);
        totalRow.setPadding(new Insets(12, 16, 16, 16));
        totalRow.setAlignment(Pos.CENTER_RIGHT);

        BorderPane panel = new BorderPane();
        panel.setTop(cartHeader);
        panel.setCenter(itemsScroll);
        panel.setBottom(totalRow);

        ScrollPane outer = new ScrollPane(panel);
        outer.setFitToWidth(true);
        outer.setFitToHeight(true);
        return outer;
    }

    /** Round 8 "richer inside-table header" row shown above the items list: read-only order type
     * (there is no endpoint to change an order's type after creation, so this is text, never a
     * tab-switcher), a live count of non-cancelled/non-voided lines, a "KOT #n" badge for the most
     * recent kitchen ticket once anything has been sent at least once, and the reprint action next
     * to it. All of it is populated by {@link #renderOrder} on every re-render. */
    private HBox buildCartHeader() {
        orderTypeLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #333;");
        itemCountLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #777;");

        kotBadgeLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white; "
                + "-fx-background-color: #e67e22; -fx-padding: 3 8 3 8; -fx-background-radius: 10;");
        kotBadgeLabel.setVisible(false);
        kotBadgeLabel.setManaged(false);

        printKotButton.setOnAction(e -> printKot());
        printKotButton.setVisible(false);
        printKotButton.setManaged(false);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(10, orderTypeLabel, itemCountLabel, kotBadgeLabel, spacer, printKotButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 16, 10, 16));
        header.setStyle("-fx-background-color: #f9f9f9; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    /** Round 8 "Print KOT" reprint action - builds the kitchen ticket client-side from whatever
     * {@link #currentOrder} already holds (fully loaded already; no new server call) and shows it
     * exactly like every other receipt in this app. Deliberately never calls {@link #sendToKitchen()}
     * or any other mutation - this is a pure reprint of what has already been sent, gated by
     * {@link #renderOrder} to only be reachable once at least one item carries a {@code kotNumber}. */
    private void printKot() {
        if (currentOrder == null) {
            return;
        }
        boolean hasBeenSent = currentOrder.items().stream().anyMatch(i -> i.kotNumber() != null);
        if (!hasBeenSent) {
            return;
        }
        String kotText = ReceiptPrinter.buildKotText(currentOrder, 40);
        // Round 11: if a printer is configured (Settings' "Default Printer"), print straight to it
        // with no dialog - the same "cashier is mid-flow, don't make them click through a picker"
        // reasoning as BillingView's new auto-printed receipt. Falls back to today's preview/print
        // dialog whenever nothing is configured or the silent print itself fails, so a KOT is never
        // silently lost - only ever not silently printed.
        if (receiptPrinterName == null || receiptPrinterName.isBlank() || !ReceiptPrinter.printSilently(receiptPrinterName, kotText)) {
            ReceiptPrinter.show("KOT - " + currentOrder.orderNumber(), kotText);
        }
    }

    /** Round 9 bottom action bar - visual/functional parity with the reference POS's Save/Save &amp;
     * Print/Save &amp; EBill/KOT/KOT &amp; Print row, adapted to how this app actually persists an
     * order: every add/remove/quantity change already saves to the server the instant it happens
     * (see this class's own javadoc - "no client-side ... math", same rule extends to "no
     * client-side draft state" either), so there is no separate unsaved-draft to commit. "Save"
     * here means "I'm done editing, back to the floor"; "KOT" is the existing
     * {@link #sendToKitchen()} action already offered by {@link #primaryActionButton} up top, just
     * also reachable from this bottom row like the reference screenshot; "Bill Table" is the new
     * direct-to-billing shortcut (see {@link #onBillNow}'s javadoc). */
    private HBox buildBottomActionBar() {
        Button saveButton = new Button("Save");
        saveButton.setOnAction(e -> onBack.run());

        saveAndPrintButton.setOnAction(e -> { printKot(); onBack.run(); });

        eBillButton.setOnAction(e -> showEBillPreview());

        kotButton.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white; -fx-font-weight: bold;");
        kotButton.setOnAction(e -> sendToKitchen());

        kotAndPrintButton.setOnAction(e -> sendToKitchenThenPrint());

        billTableButton.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
        billTableButton.setOnAction(e -> onBillNow.accept(currentOrder));

        // Round 11 - "in the dine in there should be add customer option" - a front-facing quick
        // action alongside the other common actions here, rather than buried in a menu.
        Button addCustomerButton = new Button("Add Customer");
        addCustomerButton.setOnAction(e -> customerDialog());

        HBox bar = new HBox(10, saveButton, addCustomerButton, saveAndPrintButton, eBillButton, kotButton, kotAndPrintButton, billTableButton);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(12, 24, 12, 24));
        bar.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    /** Same as {@link #sendToKitchen()} but chains {@link #printKot()} once the send actually
     * succeeds and this screen has re-rendered from the response - kept as its own method (rather
     * than a print-after flag threaded through {@code sendToKitchen}) since that method is also
     * called from the top header's {@code primaryActionButton}, which must never auto-print. */
    private void sendToKitchenThenPrint() {
        if (mutationInFlight) {
            return;
        }
        setMutationInFlight(true);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/orders/" + currentOrder.id() + "/send-to-kitchen?version=" + currentOrder.version(), null);
                OrderDtos.OrderDto updated = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> { setMutationInFlight(false); renderOrder(updated); printKot(); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
            }
        }, "chefpay-send-kitchen-print");
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 11 - "add customer" action, reachable any time this order is still open (not just at
     * creation) - see {@code OrderService#updateCustomerDetails}'s javadoc for why Dine In
     * specifically needs this (unlike Delivery/Pick Up/Online, it has no customer-capture step at
     * creation). Pre-fills from whatever's already on the order, so this doubles as "edit customer"
     * once something's been saved. */
    private void customerDialog() {
        if (mutationInFlight || currentOrder == null) {
            return;
        }
        Dialog<OrderDtos.UpdateCustomerRequest> dialog = new Dialog<>();
        dialog.setTitle("Customer Details");
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField nameField = new TextField(currentOrder.customerName() == null ? "" : currentOrder.customerName());
        nameField.setPromptText("Customer name");
        TextField phoneField = new TextField(currentOrder.customerPhone() == null ? "" : currentOrder.customerPhone());
        phoneField.setPromptText("Phone number");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), nameField);
        grid.addRow(1, new Label("Phone"), phoneField);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> button == saveType
                ? new OrderDtos.UpdateCustomerRequest(nameField.getText().trim(), phoneField.getText().trim(), currentOrder.version())
                : null);

        dialog.showAndWait().ifPresent(request -> {
            setMutationInFlight(true);
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.patch("/api/orders/" + currentOrder.id() + "/customer", request);
                    OrderDtos.OrderDto updated = apiClient.convert(data, OrderDtos.OrderDto.class);
                    Platform.runLater(() -> { setMutationInFlight(false); renderOrder(updated); });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
                }
            }, "chefpay-update-customer");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /** Round 9 "Save & EBill" - this app has no live email/SMS/WhatsApp gateway (a real
     * integration project of its own, same caveat as {@code Restaurant}'s aggregator-toggle
     * javadoc), so rather than silently pretending to send something, this shows the same
     * running-order text a KOT print would use in a copyable preview, with an honest note that
     * staff share it themselves via whatever channel they already use. Kept as a real, working
     * (if modest) feature instead of a decorative button that does nothing. */
    private void showEBillPreview() {
        String text = ReceiptPrinter.buildKotText(currentOrder, 40);
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(40);
        area.setPrefRowCount(20);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Share Bill - " + currentOrder.orderNumber());
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("Copy this and share it via WhatsApp/SMS/Email - no messaging gateway is connected yet, so nothing is sent automatically."),
                area));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    /** Keeps the Round 9 bottom-bar buttons' enabled state in sync with order state, mirroring how
     * {@link #updatePrimaryAction} already owns {@link #primaryActionButton} - called from the
     * same place, every {@link #renderOrder}. */
    private void updateBottomBar(OrderDtos.OrderDto order) {
        boolean hasAnyUnsentItems = order.items().stream().anyMatch(i -> "ADDED".equals(i.status()));
        boolean hasBeenSentToKitchen = order.items().stream().anyMatch(i -> i.kotNumber() != null);
        long visibleItemCount = order.items().stream()
                .filter(i -> !"CANCELLED".equals(i.status()) && !"VOIDED".equals(i.status())).count();
        kotButton.setDisable(!hasAnyUnsentItems);
        kotAndPrintButton.setDisable(!hasAnyUnsentItems);
        saveAndPrintButton.setDisable(!hasBeenSentToKitchen);
        eBillButton.setDisable(visibleItemCount == 0);
        billTableButton.setDisable(!BILLABLE_STATUSES.contains(order.status()));
    }

    private void loadMenu() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/menu");
                List<MenuDtos.CategoryDto> categories = apiClient.convertList(data, MenuDtos.CategoryDto.class);
                // Round 11: keep the offline cache warm from every successful live load - see
                // TableMatrixView.reload()'s identical comment for why this isn't only SyncEngine's job.
                com.chefpay.javafx.client.LocalDatabase.putCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_MENU, data.toString());
                Platform.runLater(() -> renderMenu(categories));
            } catch (ApiException ex) {
                // Round 11: "runs lightly if offline" - a waiter mid-shift with no connection still
                // needs to be able to see the menu and keep taking orders against whatever was cached
                // at login/last reconnect, rather than staring at a blank error screen.
                com.chefpay.javafx.client.LocalDatabase.CachedPayload cached =
                        com.chefpay.javafx.client.LocalDatabase.readCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_MENU);
                if (cached != null) {
                    try {
                        List<MenuDtos.CategoryDto> categories = apiClient.convertList(apiClient.parseCached(cached.payloadJson()), MenuDtos.CategoryDto.class);
                        Platform.runLater(() -> {
                            renderMenu(categories);
                            menuGrid.getChildren().add(0, new Label("Offline - showing menu as of last sync ("
                                    + cached.cachedAt() + "). New items/price changes made elsewhere won't show until reconnected."));
                        });
                        return;
                    } catch (RuntimeException parseEx) {
                        // Corrupt/unparseable cache entry - fall through to the plain error below.
                    }
                }
                Platform.runLater(() -> menuGrid.getChildren().setAll(new Label("Could not load menu: " + ex.getMessage())));
            }
        }, "chefpay-menu-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderMenu(List<MenuDtos.CategoryDto> categories) {
        this.loadedCategories = categories;
        if (selectedCategory != null) {
            // Re-select whichever category is currently open, using the freshest copy of it (an
            // item could have become unavailable, etc.) - fall back below to auto-selecting the
            // first category if it was deleted/deactivated out from under the waiter mid-order.
            selectedCategory = categories.stream()
                    .filter(c -> c.id().equals(selectedCategory.id())).findFirst().orElse(null);
        }
        if (selectedCategory == null && !categories.isEmpty()) {
            // Auto-select the first category so the item grid is never blank on open.
            selectedCategory = categories.get(0);
        }
        renderCategorySidebar(categories);
        if (selectedCategory != null) {
            renderCategoryItems(selectedCategory);
        } else {
            menuGrid.getChildren().clear();
        }
    }

    /** Round 8: the persistent nav column - one button per active category, in {@code
     * displayOrder} (same order Menu Management already lets an admin configure), the currently
     * selected one visually highlighted with a lighter fill and a left accent bar. */
    private void renderCategorySidebar(List<MenuDtos.CategoryDto> categories) {
        categorySidebar.getChildren().clear();
        for (MenuDtos.CategoryDto category : categories) {
            categorySidebar.getChildren().add(buildCategorySidebarButton(category));
        }
    }

    private Button buildCategorySidebarButton(MenuDtos.CategoryDto category) {
        boolean selected = selectedCategory != null && selectedCategory.id().equals(category.id());
        Button tile = new Button(category.name());
        tile.setMaxWidth(Double.MAX_VALUE);
        tile.setAlignment(Pos.CENTER_LEFT);
        tile.setWrapText(true);
        tile.setStyle(selected
                ? "-fx-background-color: #3d566e; -fx-text-fill: white; -fx-font-weight: bold; "
                        + "-fx-border-color: #e67e22; -fx-border-width: 0 0 0 4; -fx-background-radius: 4;"
                : "-fx-background-color: #2c3e50; -fx-text-fill: #ecf0f1; -fx-background-radius: 4;");
        tile.setOnAction(e -> {
            selectedCategory = category;
            renderCategorySidebar(loadedCategories);
            renderCategoryItems(category);
        });
        return tile;
    }

    /** Fills {@link #menuGrid} with this category's item tiles - unchanged tap-to-add-1 behavior
     * from before the persistent sidebar existed, just triggered by a sidebar click instead of a
     * tile tap ("< Categories" no longer needed since the sidebar is always visible). */
    private void renderCategoryItems(MenuDtos.CategoryDto category) {
        menuGrid.getChildren().clear();
        for (MenuDtos.ItemDto item : category.items()) {
            menuGrid.getChildren().add(buildMenuTile(item));
        }
    }

    /** Round 9: a thin food-type strip (green/yellow/red) sits on top of every tile so a waiter
     * can tell veg/egg/non-veg apart at a glance while adding items, matching the reference POS's
     * item-grid coloring. Returns a plain {@code Node} (not {@code Button}) since the strip needs
     * to sit above the tile in its own small VBox - {@link #renderCategoryItems} only ever adds
     * this to a {@code FlowPane}, which accepts any {@code Node}. */
    private Node buildMenuTile(MenuDtos.ItemDto item) {
        String priceLabel = item.halfPrice() != null ? "₹" + item.halfPrice() + " / ₹" + item.price() : "₹" + item.price();
        Button tile = new Button(item.name() + "\n" + priceLabel);
        tile.setPrefSize(140, 70);
        tile.setWrapText(true);
        tile.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        boolean available = item.available() && item.active();
        tile.setDisable(!available);
        tile.setStyle(available
                ? "-fx-background-color: white; -fx-border-color: #ccc; -fx-border-radius: 0 0 8 8;"
                : "-fx-background-color: #eee; -fx-text-fill: #999;");
        // Items with a configured half-price ask which portion first (a genuinely different,
        // less common decision); everything else keeps the original fast "tap adds 1" behavior -
        // see the quantity +/- controls in buildItemRow for adding more than one afterward.
        tile.setOnAction(e -> {
            if (item.halfPrice() != null) {
                portionChoiceDialog(item);
            } else {
                addItem(item, null);
            }
        });

        Region strip = new Region();
        strip.setPrefHeight(4);
        strip.setMinHeight(4);
        strip.setMaxWidth(Double.MAX_VALUE);
        strip.setStyle("-fx-background-color: " + foodTypeColor(item.foodType()) + "; -fx-background-radius: 8 8 0 0;");

        VBox wrapper = new VBox(strip, tile);
        wrapper.setPrefWidth(140);
        return wrapper;
    }

    /** Green = VEG, yellow = EGG ("half veg" in the reference POS's own terminology), red =
     * NON_VEG - a missing/unrecognized value falls back to green rather than red, since an
     * ambiguous classification defaulting to "looks non-veg" would be the more alarming mistake
     * for a vegetarian guest. */
    private String foodTypeColor(String foodType) {
        if (foodType == null) {
            return "#27ae60";
        }
        return switch (foodType) {
            case "NON_VEG" -> "#c0392b";
            case "EGG" -> "#f1c40f";
            default -> "#27ae60";
        };
    }

    private void portionChoiceDialog(MenuDtos.ItemDto item) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Add " + item.name());
        dialog.setHeaderText("Which portion?");
        ButtonType fullType = new ButtonType("Full  ₹" + item.price());
        ButtonType halfType = new ButtonType("Half  ₹" + item.halfPrice());
        dialog.getButtonTypes().setAll(fullType, halfType, ButtonType.CANCEL);
        dialog.showAndWait().ifPresent(button -> {
            if (button == fullType) {
                addItem(item, null);
            } else if (button == halfType) {
                addItem(item, "HALF");
            }
        });
    }

    private void addItem(MenuDtos.ItemDto item, String portion) {
        if (mutationInFlight) {
            return;
        }
        // Tapping a tile that's already sitting un-sent on this same order (same menu item, same
        // Full/Half portion, no special instructions attached) bumps that line's quantity instead
        // of adding a second "1 x Coke" line next to the first - repeated taps land as "4 x Coke",
        // exactly like the +/- stepper in buildItemRow already does, rather than four separate
        // rows the cashier/kitchen would have to read as one order. Only ADDED lines are eligible -
        // an already-SENT line can't be edited in place (server-enforced), so a new tap after that
        // point correctly starts a fresh line for the next kitchen round, same as before this fix.
        String expectedModifiers = "HALF".equalsIgnoreCase(portion) ? "Half" : null;
        OrderDtos.OrderItemDto existing = currentOrder.items().stream()
                .filter(i -> "ADDED".equals(i.status()))
                .filter(i -> i.menuItemId().equals(item.id()))
                .filter(i -> i.specialInstructions() == null)
                .filter(i -> java.util.Objects.equals(i.modifiersSummary(), expectedModifiers))
                .findFirst().orElse(null);
        if (existing != null) {
            updateItemQuantity(existing, existing.quantity().add(BigDecimal.ONE));
            return;
        }
        setMutationInFlight(true);
        var request = new OrderDtos.AddItemRequest(item.id(), BigDecimal.ONE, null, portion, currentOrder.version());
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/orders/" + currentOrder.id() + "/items", request);
                OrderDtos.OrderDto updated = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> { setMutationInFlight(false); renderOrder(updated); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
            }
        }, "chefpay-add-item");
        worker.setDaemon(true);
        worker.start();
    }

    /** POS-style "+"/"-" quantity adjustment on an already-added (not yet sent) cart line - the
     * faster way to get to "10 chapatis" than tapping the menu tile ten times, and structurally
     * can't race the way repeated tile-taps used to (see {@link #mutationInFlight}'s javadoc).
     * Dropping to zero removes the line the same way the "x" button always has. */
    private void updateItemQuantity(OrderDtos.OrderItemDto item, BigDecimal newQuantity) {
        if (mutationInFlight) {
            return;
        }
        if (newQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            removeItem(item);
            return;
        }
        setMutationInFlight(true);
        var request = new OrderDtos.UpdateItemRequest(newQuantity, null, currentOrder.version());
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.patch("/api/orders/" + currentOrder.id() + "/items/" + item.id(), request);
                OrderDtos.OrderDto updated = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> { setMutationInFlight(false); renderOrder(updated); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
            }
        }, "chefpay-update-item-qty");
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 8 special-instructions quick-pick dialog for an already-added (not yet sent) cart
     * line - free-text {@code TextArea} pre-filled with the line's current instructions, plus
     * quick-pick preset buttons loaded from {@code GET /api/special-notes} (fetched fresh every
     * time the dialog opens, in a background thread like every other network call in this file).
     * Clicking a preset appends it to the text area (or fills it, if empty) rather than replacing
     * free typing already there. Confirming posts through {@link #updateItemNote}; cancelling (or
     * the notes fetch failing) leaves the line untouched - this is a convenience feature, not
     * critical path, so a failed fetch just means no quick-pick buttons rather than a blocked dialog. */
    private void openNoteDialog(OrderDtos.OrderItemDto item) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Special Instructions");
        dialog.setHeaderText(item.menuItemName());
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        TextArea textArea = new TextArea(item.specialInstructions() != null ? item.specialInstructions() : "");
        textArea.setWrapText(true);
        textArea.setPrefRowCount(3);
        textArea.setPrefWidth(300);

        VBox quickPickBox = new VBox(4);

        VBox content = new VBox(8,
                new Label("Instructions:"), textArea,
                new Label("Quick picks:"), quickPickBox);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(buttonType -> buttonType == ButtonType.OK ? textArea.getText() : null);

        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/special-notes");
                List<SpecialNoteDtos.SpecialNoteDto> notes = apiClient.convertList(data, SpecialNoteDtos.SpecialNoteDto.class);
                Platform.runLater(() -> {
                    for (SpecialNoteDtos.SpecialNoteDto note : notes) {
                        Button pick = new Button(note.text());
                        pick.setMaxWidth(Double.MAX_VALUE);
                        pick.setOnAction(e -> {
                            String current = textArea.getText();
                            textArea.setText(current == null || current.isBlank() ? note.text() : current + ", " + note.text());
                        });
                        quickPickBox.getChildren().add(pick);
                    }
                });
            } catch (ApiException ignored) {
                // Convenience feature only - the dialog still works fine with just free text.
            }
        }, "chefpay-special-notes-load");
        worker.setDaemon(true);
        worker.start();

        dialog.showAndWait().ifPresent(newNote -> updateItemNote(item, newNote));
    }

    /** Posts an already-added (not yet sent) line's special instructions via the same {@code
     * PATCH /api/orders/{id}/items/{itemId}} endpoint {@link #updateItemQuantity} already uses -
     * that endpoint's {@code quantity} field is "leave unchanged" when null (confirmed against
     * {@code OrderService#updateItem} on the server), but this passes the item's own current
     * quantity explicitly anyway, to be safe rather than relying on that null-means-unchanged
     * convention for a request this method didn't originate. */
    private void updateItemNote(OrderDtos.OrderItemDto item, String newNote) {
        if (mutationInFlight) {
            return;
        }
        setMutationInFlight(true);
        var request = new OrderDtos.UpdateItemRequest(item.quantity(), newNote, currentOrder.version());
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.patch("/api/orders/" + currentOrder.id() + "/items/" + item.id(), request);
                OrderDtos.OrderDto updated = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> { setMutationInFlight(false); renderOrder(updated); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
            }
        }, "chefpay-update-item-note");
        worker.setDaemon(true);
        worker.start();
    }

    private void removeItem(OrderDtos.OrderItemDto item) {
        if (mutationInFlight) {
            return;
        }
        setMutationInFlight(true);
        var request = new CancelItemPayload(null, currentOrder.version());
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.delete("/api/orders/" + currentOrder.id() + "/items/" + item.id(), request);
                OrderDtos.OrderDto updated = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> { setMutationInFlight(false); renderOrder(updated); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
            }
        }, "chefpay-remove-item");
        worker.setDaemon(true);
        worker.start();
    }

    private record CancelItemPayload(String reason, long orderVersion) {
    }

    private void sendToKitchen() {
        if (mutationInFlight) {
            return;
        }
        setMutationInFlight(true);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/orders/" + currentOrder.id() + "/send-to-kitchen?version=" + currentOrder.version(), null);
                OrderDtos.OrderDto updated = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> { setMutationInFlight(false); renderOrder(updated); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
            }
        }, "chefpay-send-kitchen");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The waiter's "food's out, table's done" action - walks the order's status forward one step
     * at a time (the only kind of transition {@code OrderStatus.canTransitionTo} allows) from
     * wherever it currently sits in {@link #ORDER_PROGRESS_CHAIN} all the way to SERVED in a
     * single click, rather than making them click through ACCEPTED/PREPARING/READY individually.
     * Once SERVED, the order becomes visible in {@code BillingView}'s list and its own "Request
     * Bill" button takes over from there - this screen's job ends at SERVED.
     */
    private void markOrderServed() {
        if (mutationInFlight) {
            return;
        }
        setMutationInFlight(true);
        OrderDtos.OrderDto startingOrder = currentOrder;
        Thread worker = new Thread(() -> {
            try {
                OrderDtos.OrderDto order = startingOrder;
                int fromIndex = ORDER_PROGRESS_CHAIN.indexOf(order.status());
                if (fromIndex < 0) {
                    Platform.runLater(() -> setMutationInFlight(false));
                    return; // already past this point (or an unexpected status) - nothing to do
                }
                for (int i = fromIndex + 1; i < ORDER_PROGRESS_CHAIN.size(); i++) {
                    var request = new OrderDtos.UpdateOrderStatusRequest(ORDER_PROGRESS_CHAIN.get(i), null, order.version());
                    var data = apiClient.patch("/api/orders/" + order.id() + "/status", request);
                    order = apiClient.convert(data, OrderDtos.OrderDto.class);
                }
                OrderDtos.OrderDto finalOrder = order;
                Platform.runLater(() -> { setMutationInFlight(false); renderOrder(finalOrder); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
            }
        }, "chefpay-mark-served");
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 9's {@link #ORDER_PROGRESS_CHAIN} starting one step earlier, at PLACED - see {@link
     * #billDirectlySkippingKitchen}, the only caller. */
    private static final List<String> DIRECT_TO_BILL_CHAIN = List.of(
            "PLACED", "SENT_TO_KITCHEN", "ACCEPTED", "PREPARING", "READY", "SERVED");

    /**
     * Round 11 - "when physically verified then can directly bill (without kitchen interference)".
     * Only reachable when {@code Restaurant#kotOptionalEnabled} is on and the order is still at
     * PLACED (see {@link #updatePrimaryAction}'s gate on {@link #billDirectlyButton}). Walks the
     * SAME one-step-at-a-time status chain {@link #markOrderServed} uses, starting one step
     * earlier - but critically goes through the GENERIC {@code PATCH /api/orders/{id}/status}
     * endpoint the whole way, never the dedicated "send to kitchen" endpoint {@link #sendToKitchen}
     * calls. That's what actually skips the kitchen: no KOT number is ever assigned to any item and
     * the Kitchen Display is never notified (see {@code OrderService#updateOrderStatus}'s matching
     * server-side gate, which is what actually enforces the restaurant's opt-in - this client-side
     * check is only about what button to show). Once the walk reaches SERVED, jumps straight into
     * Billing via {@link #onBillNow} - "can directly bill" - rather than leaving the waiter to find
     * this order again from Tables.
     */
    private void billDirectlySkippingKitchen() {
        if (mutationInFlight) {
            return;
        }
        OrderDtos.OrderDto startingOrder = currentOrder;
        if (startingOrder == null || !"PLACED".equals(startingOrder.status())) {
            return;
        }
        setMutationInFlight(true);
        Thread worker = new Thread(() -> {
            try {
                OrderDtos.OrderDto order = startingOrder;
                for (int i = 1; i < DIRECT_TO_BILL_CHAIN.size(); i++) {
                    var request = new OrderDtos.UpdateOrderStatusRequest(DIRECT_TO_BILL_CHAIN.get(i), null, order.version());
                    var data = apiClient.patch("/api/orders/" + order.id() + "/status", request);
                    order = apiClient.convert(data, OrderDtos.OrderDto.class);
                }
                OrderDtos.OrderDto finalOrder = order;
                Platform.runLater(() -> { setMutationInFlight(false); onBillNow.accept(finalOrder); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleMutationError(ex); });
            }
        }, "chefpay-bill-directly");
        worker.setDaemon(true);
        worker.start();
    }

    /** Disables the menu panel (sidebar + item grid), cart panel, and every action button for the
     * duration of any in-flight mutation - see {@link #mutationInFlight}'s javadoc for exactly why.
     *
     * <p>Only the ON side is unconditional here for the action buttons (Send to Kitchen/Mark
     * Served, KOT, KOT &amp; Print, Save &amp; Print, Save &amp; EBill, Bill Table, Print KOT) -
     * going {@code inFlight = true} always force-disables them, immediately, before the network
     * call even starts. The OFF side is deliberately left to {@link #updatePrimaryAction}/
     * {@link #updateBottomBar} (via {@link #renderOrder} on success, {@link #refetch} on a version
     * conflict, or the explicit calls added to {@link #handleMutationError}'s plain-error branch) -
     * those already know the one correct enabled/disabled state for the order's actual current
     * data, so blanket-enabling here too would either fight that logic or, worse, briefly show a
     * button enabled when the order state says it shouldn't be.
     *
     * <p>Previously this method only disabled the menu/cart panels, not these action buttons
     * themselves - so a cashier tapping e.g. "Mark Order Served" on a slow connection saw a fully
     * clickable button with no feedback while the request was in flight, tapped it again (and
     * again), and each extra tap was silently swallowed by the {@link #mutationInFlight} guard with
     * no error, no dialog, nothing - until the *original* tap's request finally resolved. That
     * looked exactly like "it took three clicks to register," when the very first click was
     * working the whole time. Force-disabling immediately closes that gap (the same fix applied to
     * RetailPOS's equivalent POS screen, for the identical underlying reason). */
    private void setMutationInFlight(boolean inFlight) {
        this.mutationInFlight = inFlight;
        menuGrid.setDisable(inFlight);
        categorySidebar.setDisable(inFlight);
        itemsBox.setDisable(inFlight);
        if (inFlight) {
            primaryActionButton.setDisable(true);
            billDirectlyButton.setDisable(true);
            kotButton.setDisable(true);
            kotAndPrintButton.setDisable(true);
            saveAndPrintButton.setDisable(true);
            eBillButton.setDisable(true);
            billTableButton.setDisable(true);
            printKotButton.setDisable(true);
        }
    }

    /** Decides what the single header action button offers next, based on order status and
     * whether any item is still sitting un-sent (§41/§42 - one obvious next step at a time). */
    private void updatePrimaryAction(OrderDtos.OrderDto order) {
        String status = order.status();
        // Direct-sale items (MenuItem#directSale) deliberately never leave ADDED - see
        // OrderService#sendToKitchen's javadoc - so they must not count as "still pending" here,
        // or this button would stay stuck offering "Send to Kitchen" forever even once every real
        // kitchen-bound item has actually been sent.
        boolean hasPendingItems = order.items().stream().anyMatch(i -> "ADDED".equals(i.status()) && !i.directSale());
        // Separate from hasPendingItems above: an order made up entirely of direct-sale items (e.g.
        // just "2x Coke") has nothing that needs real kitchen routing, but the button must still be
        // enabled so the order can advance out of DRAFT/PLACED at all - see
        // OrderService#sendToKitchen's matching comment on the server side. Safe to use for the
        // enabled-state in the ORDER_PROGRESS_CHAIN branch too: that branch is only entered when
        // hasPendingItems is already true, which this always agrees with.
        boolean hasAnyUnsentItems = order.items().stream().anyMatch(i -> "ADDED".equals(i.status()));

        // Round 11: default off every render - only the PLACED branch below turns it back on, so an
        // order that's moved past PLACED (e.g. another terminal sent it to kitchen while this screen
        // was open) never leaves this button visible.
        billDirectlyButton.setVisible(false);
        billDirectlyButton.setManaged(false);

        if ("DRAFT".equals(status) || "PLACED".equals(status)
                || (ORDER_PROGRESS_CHAIN.contains(status) && hasPendingItems)) {
            primaryActionButton.setText("Send to Kitchen");
            primaryActionButton.setDisable(!hasAnyUnsentItems);
            primaryActionButton.setOnAction(e -> sendToKitchen());
            primaryActionButton.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white; -fx-font-weight: bold;");
            setPrimaryActionVisible(true);
            // Round 11: only offered at PLACED (the actual door this walks through - see
            // billDirectlySkippingKitchen's javadoc), not DRAFT/already-sent, and only when the
            // restaurant has explicitly opted in under Settings.
            boolean showBillDirectly = kotOptionalEnabled && "PLACED".equals(status);
            billDirectlyButton.setVisible(showBillDirectly);
            billDirectlyButton.setManaged(showBillDirectly);
            billDirectlyButton.setDisable(!hasAnyUnsentItems);
        } else if (ORDER_PROGRESS_CHAIN.contains(status) && !"SERVED".equals(status)) {
            // Requirement: "Mark Order Served" used to be available the moment nothing was left
            // un-sent, regardless of whether the kitchen had actually accepted/prepped/served
            // anything - not in sync with what's really happening on the pass. When the restaurant
            // has kitchen sync required (Settings), this only unlocks once every real item is
            // SERVED via the KDS (see KitchenDisplayView); when it's off, the original lenient
            // behavior is kept for restaurants that don't need the extra step.
            boolean kitchenHasServedEverything = order.items().stream()
                    .filter(i -> !"CANCELLED".equals(i.status()) && !"VOIDED".equals(i.status()) && !i.directSale())
                    .allMatch(i -> "SERVED".equals(i.status()));
            boolean canMarkServed = !requireKitchenSyncForServed || kitchenHasServedEverything;

            primaryActionButton.setText("Mark Order Served");
            primaryActionButton.setDisable(!canMarkServed);
            primaryActionButton.setOnAction(e -> markOrderServed());
            primaryActionButton.setTooltip(canMarkServed ? null : new Tooltip(
                    "Waiting for the kitchen to serve every item (Settings > require kitchen sync)."));
            primaryActionButton.setStyle(canMarkServed
                    ? "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;"
                    : "-fx-background-color: #95a5a6; -fx-text-fill: white; -fx-font-weight: bold;");
            setPrimaryActionVisible(true);
        } else if ("SERVED".equals(status)) {
            primaryActionButton.setText("Sent to Billing ✓");
            primaryActionButton.setDisable(true);
            primaryActionButton.setStyle("-fx-background-color: #bbb; -fx-text-fill: white; -fx-font-weight: bold;");
            setPrimaryActionVisible(true);
        } else {
            // BILL_REQUESTED and later: this order has moved on to BillingView, nothing left to do here.
            setPrimaryActionVisible(false);
        }
    }

    private void setPrimaryActionVisible(boolean visible) {
        primaryActionButton.setVisible(visible);
        primaryActionButton.setManaged(visible);
    }

    private void handleMutationError(ApiException ex) {
        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
            // Requirement §22: never silently overwrite - refetch the latest state and let the user retry.
            refetch();
            new Alert(Alert.AlertType.WARNING,
                    "This order was updated by another terminal. Showing the latest version - please retry.").showAndWait();
        } else {
            // A transient/plain failure (network blip, validation error, etc.) never touched
            // currentOrder, so re-deriving from it is correct and immediate - no round trip needed
            // the way the VERSION_CONFLICT branch above needs one. This restores whatever
            // setMutationInFlight(true) just force-disabled (see its javadoc) to the correct
            // enabled/disabled state for the order as it actually stands, so the action buttons
            // don't stay stuck disabled after an ordinary failure - the user can just retry.
            updatePrimaryAction(currentOrder);
            updateBottomBar(currentOrder);
            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
        }
    }

    private void onOrderEvent(String rawJson) {
        try {
            JsonNode node = mapper.readTree(rawJson);
            String entityId = node.path("entityId").asText(null);
            if (entityId != null && currentOrder != null && entityId.equals(currentOrder.id().toString())) {
                refetch();
            }
        } catch (Exception ignored) {
            // malformed/unrelated event - ignore, next event or manual action will resync
        }
    }

    private void refetch() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/orders/" + currentOrder.id());
                OrderDtos.OrderDto updated = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> renderOrder(updated));
            } catch (ApiException ignored) {
                // transient - the next WS event or user action will retry
            }
        }, "chefpay-order-refetch");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderOrder(OrderDtos.OrderDto order) {
        this.currentOrder = order;
        headerLabel.setText(order.orderNumber() + (order.tableName() != null ? "  •  " + order.tableName() : ""));
        // Round 11 - "add customer" - show who the order is for right in the header the moment
        // it's set, same place a table name already shows, rather than only visible inside a dialog.
        String customerSuffix = order.customerName() == null || order.customerName().isBlank() ? ""
                : "  •  " + order.customerName() + (order.customerPhone() != null && !order.customerPhone().isBlank()
                        ? " (" + order.customerPhone() + ")" : "");
        statusLabel.setText(order.status().replace('_', ' ') + customerSuffix);
        updatePrimaryAction(order);
        updateBottomBar(order);

        orderTypeLabel.setText(order.orderType() == null ? "" : order.orderType().replace('_', ' '));

        long visibleItemCount = order.items().stream()
                .filter(i -> !"CANCELLED".equals(i.status()) && !"VOIDED".equals(i.status()))
                .count();
        itemCountLabel.setText(visibleItemCount + (visibleItemCount == 1 ? " item" : " items"));

        Long latestKotNumber = order.items().stream()
                .map(OrderDtos.OrderItemDto::kotNumber)
                .filter(java.util.Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        boolean hasBeenSentToKitchen = latestKotNumber != null;
        kotBadgeLabel.setText(hasBeenSentToKitchen ? "KOT #" + latestKotNumber : "");
        kotBadgeLabel.setVisible(hasBeenSentToKitchen);
        kotBadgeLabel.setManaged(hasBeenSentToKitchen);
        printKotButton.setVisible(hasBeenSentToKitchen);
        printKotButton.setManaged(hasBeenSentToKitchen);
        printKotButton.setDisable(!hasBeenSentToKitchen);

        itemsBox.getChildren().clear();
        for (OrderDtos.OrderItemDto item : order.items()) {
            if ("CANCELLED".equals(item.status()) || "VOIDED".equals(item.status())) {
                continue;
            }
            itemsBox.getChildren().add(buildItemRow(item));
        }
        totalLabel.setText("Total: ₹" + order.totalAmount());
    }

    private HBox buildItemRow(OrderDtos.OrderItemDto item) {
        String tag = (item.modifiersSummary() != null && !item.modifiersSummary().isBlank() ? " (" + item.modifiersSummary() + ")" : "")
                + (item.directSale() ? " [Direct Sale]" : "");
        Label name = new Label(item.quantity() + " x " + item.menuItemName() + tag);
        name.setStyle("-fx-font-size: 14px;");
        Label price = new Label("₹" + item.lineTotal());
        Label status = new Label(item.status());
        status.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // POS-style quantity +/- - only while the line hasn't been sent yet, same gating the "x"
        // remove button below has always used. Whole-unit steps only (fine for the "10 chapatis"/
        // "4 more coke" use case this exists for) - a weighed item entered with a fractional
        // quantity can still be corrected via this, it just steps by a whole 1 each tap.
        boolean adjustable = "ADDED".equals(item.status());
        Button minus = new Button("-");
        minus.setDisable(!adjustable);
        minus.setOnAction(e -> updateItemQuantity(item, item.quantity().subtract(BigDecimal.ONE)));
        Button plus = new Button("+");
        plus.setDisable(!adjustable);
        plus.setOnAction(e -> updateItemQuantity(item, item.quantity().add(BigDecimal.ONE)));
        // Round 11: the quantity number itself, shown between the +/- buttons - matches the
        // reference POS screenshots' "1-2-3" stepper look, rather than only showing the count in
        // the item name label to the left.
        Label qtyValue = new Label(item.quantity().stripTrailingZeros().toPlainString());
        qtyValue.setStyle("-fx-font-weight: bold; -fx-padding: 0 6 0 6;");
        qtyValue.setMinWidth(20);
        qtyValue.setAlignment(Pos.CENTER);
        HBox qtyStepper = new HBox(2, minus, qtyValue, plus);
        qtyStepper.setAlignment(Pos.CENTER);

        // Round 8: special-instructions quick-pick, same "still ADDED" gating as the qty stepper
        // and remove button - once a line has been sent it can no longer be edited in place.
        Button noteButton = new Button("Note");
        noteButton.setDisable(!adjustable);
        noteButton.setOnAction(e -> openNoteDialog(item));

        Button remove = new Button("x");
        remove.setDisable(!"ADDED".equals(item.status()));
        remove.setOnAction(e -> removeItem(item));

        HBox row = new HBox(12, name, spacer, status, qtyStepper, noteButton, price, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    public Parent view() {
        return root;
    }
}
