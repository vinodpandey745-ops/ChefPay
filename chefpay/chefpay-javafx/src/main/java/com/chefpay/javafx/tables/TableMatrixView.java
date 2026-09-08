package com.chefpay.javafx.tables;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.StompWebSocketClient;
import com.chefpay.javafx.client.dto.AreaDtos;
import com.chefpay.javafx.client.dto.BillingDtos;
import com.chefpay.javafx.client.dto.CustomerDtos;
import com.chefpay.javafx.client.dto.OrderDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.client.dto.TableAdminDtos;
import com.chefpay.javafx.client.dto.TableDto;
import com.chefpay.javafx.common.ReceiptPrinter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Visual table matrix (requirement §10). Tapping an AVAILABLE table opens (creates) its order and
 * hands off to the order-taking screen; tapping a table that already has an open order re-opens
 * that SAME order (the get-or-create semantics live server-side in {@code OrderService}) - this
 * is what makes it safe for a waiter and a cashier to tap the same table without creating two
 * orders.
 */
public class TableMatrixView {

    /** Table statuses at which a bill plausibly exists to print/reprint (§4's Billing screen has
     * the same "Request Bill" -> ... -> paid window). Once the guest pays, {@code BillingService}
     * closes the order and the table drops back to AVAILABLE, so this window is deliberately short. */
    private static final Set<String> PRINTABLE_STATUSES = Set.of("BILL_REQUESTED", "PAYMENT_PENDING");

    /** Statuses that represent a running order still on the table - these get the elapsed-time
     * and running-total badges baked from the matching open order (matched by tableId, same
     * lookup {@code printBillForTable} already does). AVAILABLE/RESERVED/BLOCKED tables have no
     * order to badge. */
    private static final Set<String> RUNNING_STATUSES = Set.of("OCCUPIED", "ORDER_PLACED", "PREPARING",
            "READY", "BILL_REQUESTED", "PAYMENT_PENDING");

    /** Sentinel item at the top of {@link #areaFilter} meaning "don't filter" - today's behavior,
     * unchanged default. */
    private static final String ALL_AREAS = "All Areas";

    private final BorderPane root = new BorderPane();
    private final javafx.scene.layout.FlowPane grid = new javafx.scene.layout.FlowPane(16, 16);
    private final ApiClient apiClient;
    private final Consumer<OrderDtos.OrderDto> onTableOpened;
    private final ComboBox<String> areaFilter = new ComboBox<>();

    // Cached from the last reload() so switching the area filter can re-render instantly without
    // hitting the server again - filtering is a pure client-side view over already-loaded data.
    private List<TableDto> lastTables = List.of();
    private Map<java.util.UUID, OrderDtos.OrderDto> lastOrdersByTable = Map.of();

    public TableMatrixView(ApiClient apiClient, StompWebSocketClient wsClient, Consumer<OrderDtos.OrderDto> onTableOpened) {
        this.apiClient = apiClient;
        this.onTableOpened = onTableOpened;

        Label title = new Label("Tables");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        Button delivery = new Button("+ Delivery");
        delivery.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        delivery.setOnAction(e -> quickOrderDialog("DELIVERY", "New Delivery Order"));

        Button pickup = new Button("+ Pick Up");
        pickup.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        pickup.setOnAction(e -> quickOrderDialog("TAKEAWAY", "New Pick Up Order"));

        // Logs an order that arrived through an outside channel (phone/website/aggregator dashboard)
        // that this app has no live feed into yet - see OnlineOrdersView's javadoc. Shows up on that
        // screen and, if Restaurant.autoPrintOnlineOrders is on, auto-prints a KOT the moment it's
        // created (ShellView's websocket subscription).
        Button onlineOrder = new Button("+ Online Order");
        onlineOrder.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-font-weight: bold;");
        onlineOrder.setOnAction(e -> quickOrderDialog("ONLINE_ORDER", "New Online Order"));

        // Pure client-side filter over the already-loaded table list (see render()) - non-editable,
        // unlike the free-text Section combo in TableManagementView, since this only ever picks
        // among options the server actually returned.
        areaFilter.getItems().add(ALL_AREAS);
        areaFilter.getSelectionModel().selectFirst();
        areaFilter.setOnAction(e -> render(lastTables, lastOrdersByTable));

        HBox headerRow = new HBox(16, title, refresh, delivery, pickup, onlineOrder, areaFilter);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        HBox legend = buildLegend();

        VBox header = new VBox(8, headerRow, legend);
        header.setPadding(new Insets(0, 0, 16, 0));

        grid.setPadding(new Insets(8));

        VBox content = new VBox(0, header, grid);
        content.setPadding(new Insets(24));
        root.setCenter(content);

        wsClient.subscribe("/topic/tables", (dest, body) -> reload());
        wsClient.subscribe("/topic/orders", (dest, body) -> reload());

        reload();
        loadAreas();
    }

    /** Fetches the branch's active Areas once, to populate {@link #areaFilter} - a small one-shot
     * background load alongside the table/order load in {@code reload()}, following the exact same
     * threading pattern. Areas rarely change while the floor view is open, so unlike {@code reload()}
     * this isn't re-triggered by the table/order websocket subscriptions above. A restaurant with no
     * Areas configured (or a failed fetch) just leaves "All Areas" as the only option. */
    private void loadAreas() {
        Thread worker = new Thread(() -> {
            try {
                var restaurantData = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(restaurantData, RestaurantDtos.RestaurantDto.class);
                java.util.UUID branchId = restaurant.branches().stream().findFirst()
                        .map(RestaurantDtos.BranchDto::id)
                        .orElse(null);
                if (branchId == null) {
                    return;
                }
                var areasData = apiClient.get("/api/areas?branchId=" + branchId);
                List<AreaDtos.AreaDto> areas = apiClient.convertList(areasData, AreaDtos.AreaDto.class);
                List<String> names = areas.stream()
                        .filter(AreaDtos.AreaDto::active)
                        .map(AreaDtos.AreaDto::name)
                        .toList();
                Platform.runLater(() -> {
                    String previousSelection = areaFilter.getSelectionModel().getSelectedItem();
                    areaFilter.getItems().setAll(ALL_AREAS);
                    areaFilter.getItems().addAll(names);
                    areaFilter.getSelectionModel().select(
                            previousSelection != null && areaFilter.getItems().contains(previousSelection)
                                    ? previousSelection : ALL_AREAS);
                });
            } catch (ApiException ex) {
                // Convenience filter only - leave "All Areas" as the sole option on failure.
            }
        }, "chefpay-areas-load");
        worker.setDaemon(true);
        worker.start();
    }

    /** "Table View has a status legend" - built off {@code TableTheme.legendEntries()} so it can
     * never drift out of sync with the tile colors it's explaining. */
    private HBox buildLegend() {
        HBox legend = new HBox(14);
        legend.setAlignment(Pos.CENTER_LEFT);
        for (Map.Entry<String, Color> entry : TableTheme.legendEntries()) {
            Circle dot = new Circle(6, entry.getValue());
            Label label = new Label(entry.getKey().replace('_', ' '));
            label.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            HBox item = new HBox(4, dot, label);
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        return legend;
    }

    public void reload() {
        // Round 11: scope to the cashier's currently-picked branch (ShellView's switcher), for a
        // restaurant CHAIN - null (the common single-branch case, and every deployment before this
        // round) means "no filter", identical to today's behavior.
        java.util.UUID branchId = com.chefpay.javafx.client.SessionStore.get().getCurrentBranchId();
        String branchSuffix = branchId == null ? "" : "?branchId=" + branchId;
        Thread worker = new Thread(() -> {
            try {
                var tablesData = apiClient.get("/api/tables" + branchSuffix);
                List<TableDto> tables = apiClient.convertList(tablesData, TableDto.class);
                // Round 11: keep the offline cache warm from every successful live load, not just
                // SyncEngine's own timer - the floor layout rarely changes mid-shift, so whichever
                // screen happened to load it last (this one, or SyncEngine's login-time/reconnect
                // refresh) is an equally good source for the fallback below.
                com.chefpay.javafx.client.LocalDatabase.putCached(
                        com.chefpay.javafx.client.SyncEngine.CACHE_KEY_TABLES, tablesData.toString());

                // Open orders carry tableId/createdAt/totalAmount, needed for the elapsed-time and
                // running-total badges below - the table itself doesn't store a back-reference to
                // its current order (same lookup printBillForTable already does).
                var ordersData = apiClient.get("/api/orders" + branchSuffix);
                List<OrderDtos.OrderDto> openOrders = apiClient.convertList(ordersData, OrderDtos.OrderDto.class);
                Map<java.util.UUID, OrderDtos.OrderDto> ordersByTable = new HashMap<>();
                for (OrderDtos.OrderDto order : openOrders) {
                    if (order.tableId() != null) {
                        ordersByTable.put(order.tableId(), order);
                    }
                }

                Platform.runLater(() -> render(tables, ordersByTable));
            } catch (ApiException ex) {
                // Round 11: "if pos is offline it runs lightly" - fall back to the last cached floor
                // layout (synced by SyncEngine at login and whenever connectivity returns) instead of
                // leaving the screen blank. Table occupancy/open orders are live transactional state
                // that can't be safely shown from a stale cache, so this only recovers the layout
                // itself - every tile renders as its cached last-known status until the connection
                // returns and a normal reload() runs.
                com.chefpay.javafx.client.LocalDatabase.CachedPayload cached =
                        com.chefpay.javafx.client.LocalDatabase.readCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_TABLES);
                if (cached != null) {
                    try {
                        List<TableDto> tables = apiClient.convertList(apiClient.parseCached(cached.payloadJson()), TableDto.class);
                        Platform.runLater(() -> {
                            render(tables, Map.of());
                            grid.getChildren().add(0, new Label("Offline - showing floor layout as of last sync ("
                                    + cached.cachedAt() + "). Live order status unavailable until reconnected."));
                        });
                        return;
                    } catch (RuntimeException parseEx) {
                        // Corrupt/unparseable cache entry - fall through to the plain error below
                        // rather than crashing the reload.
                    }
                }
                Platform.runLater(() -> grid.getChildren().setAll(new Label("Could not load tables: " + ex.getMessage())));
            }
        }, "chefpay-tables-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<TableDto> tables, Map<java.util.UUID, OrderDtos.OrderDto> ordersByTable) {
        this.lastTables = tables;
        this.lastOrdersByTable = ordersByTable;
        grid.getChildren().clear();
        for (TableDto table : filterByArea(tables)) {
            grid.getChildren().add(buildTile(table, ordersByTable.get(table.id())));
        }
    }

    /** {@code section} is free text (see class javadoc / Area's own javadoc), so this is a plain
     * case-insensitive exact match against the selected Area's name - no partial-match cleverness. */
    private List<TableDto> filterByArea(List<TableDto> tables) {
        String selected = areaFilter.getSelectionModel().getSelectedItem();
        if (selected == null || ALL_AREAS.equals(selected)) {
            return tables;
        }
        return tables.stream()
                .filter(table -> selected.equalsIgnoreCase(table.section()))
                .toList();
    }

    private StackPane buildTile(TableDto table, OrderDtos.OrderDto order) {
        Rectangle bg = new Rectangle(140, 110);
        bg.setArcWidth(12);
        bg.setArcHeight(12);
        bg.setFill(TableTheme.colorFor(table.status()));

        Label name = new Label(table.name());
        name.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label capacity = new Label(table.seatingCapacity() + " seats");
        capacity.setStyle("-fx-text-fill: white;");
        Label status = new Label(table.status().replace('_', ' '));
        status.setStyle("-fx-text-fill: white; -fx-font-size: 11px;");

        VBox labels = new VBox(4, name, capacity, status);
        labels.setAlignment(Pos.CENTER);

        // Elapsed-time / running-total badges - the two things a manager glancing at the floor
        // actually wants to know about a running table without tapping in ("how long have they
        // been here" and "what do they owe so far"). Only shown when there's a matching order and
        // the table is in a state where that order is actually running.
        if (order != null && RUNNING_STATUSES.contains(table.status())) {
            Label elapsed = new Label(elapsedLabel(order.createdAt()));
            elapsed.setStyle("-fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold;");
            Label runningTotal = new Label("₹" + (order.totalAmount() == null ? "0" : order.totalAmount()));
            runningTotal.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold;");
            labels.getChildren().addAll(elapsed, runningTotal);
        }

        StackPane tile = new StackPane(bg, labels);
        tile.setPrefSize(140, 110);
        tile.setOnMouseClicked(e -> openTable(table));
        tile.setStyle("-fx-cursor: hand;");

        if (PRINTABLE_STATUSES.contains(table.status()) && SessionStore.get().hasPermission("BILLING_MANAGE")) {
            Button print = new Button("🖨");
            print.setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-padding: 2 6; -fx-font-size: 12px; -fx-cursor: hand;");
            print.setTooltip(new javafx.scene.control.Tooltip("Print bill"));
            // Consume the click here so it doesn't also bubble up to the tile's own
            // setOnMouseClicked above and open the table instead of printing.
            print.setOnMouseClicked(e -> {
                e.consume();
                printBillForTable(table);
            });
            StackPane.setAlignment(print, Pos.TOP_RIGHT);
            StackPane.setMargin(print, new Insets(6));
            tile.getChildren().add(print);
        }

        // Reservation quick-action (requirement: "option to reserve table to pre-booked order").
        // Uses the same PATCH /api/tables/{id} endpoint (and TABLE_MANAGE gate) the Table Setup
        // screen already uses to change status - no new server endpoint or permission needed. Only
        // offered from AVAILABLE (nothing sensible to "reserve" once an order is already running).
        if ("AVAILABLE".equals(table.status()) && SessionStore.get().hasPermission("TABLE_MANAGE")) {
            Button reserve = new Button("🔖");
            reserve.setStyle("-fx-background-color: rgba(255,255,255,0.85); -fx-padding: 2 6; -fx-font-size: 12px; -fx-cursor: hand;");
            reserve.setTooltip(new javafx.scene.control.Tooltip("Reserve for a pre-booked guest"));
            reserve.setOnMouseClicked(e -> {
                e.consume();
                reserveTable(table);
            });
            StackPane.setAlignment(reserve, Pos.TOP_LEFT);
            StackPane.setMargin(reserve, new Insets(6));
            tile.getChildren().add(reserve);
        }

        return tile;
    }

    /** Rounded-minutes/hours "how long have they been here" badge off the order's createdAt -
     * deliberately coarse (minutes, then hours+minutes past 60) rather than a live-ticking clock,
     * since this label only refreshes when {@code reload()} runs (table/order websocket events or
     * the Refresh button), not every second. */
    private String elapsedLabel(LocalDateTime createdAt) {
        if (createdAt == null) {
            return "";
        }
        long minutes = Duration.between(createdAt, LocalDateTime.now()).toMinutes();
        if (minutes < 0) {
            minutes = 0;
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /** Delivery/Pickup quick-order (commercial-POS parity item) - no table involved, so this posts
     * straight to {@code POST /api/orders} with {@code tableId=null} the same way
     * {@code openTableOrder} does for a dine-in table, just with a customer name/phone attached.
     * The phone field doubles as a customer lookup against {@code GET /api/customers?query=} so
     * front-of-house doesn't have to retype a returning guest's name - matches the reference POS's
     * Delivery/Pick Up buttons feeding off its own customer directory. */
    private void quickOrderDialog(String orderType, String title) {
        Dialog<OrderDtos.CreateOrderRequest> dialog = new Dialog<>();
        dialog.setTitle(title);
        ButtonType createType = new ButtonType("Create Order", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField phone = new TextField();
        phone.setPromptText("Phone number");
        TextField name = new TextField();
        name.setPromptText("Customer name");
        Label lookupStatus = new Label();
        lookupStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        Button lookup = new Button("Look Up");
        lookup.setOnAction(e -> lookupCustomerByPhone(phone.getText(), name, lookupStatus));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Phone"), phone, lookup);
        grid.addRow(1, new Label("Name"), name);
        grid.addRow(2, new Label(""), lookupStatus);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter a customer name.").showAndWait();
                return null;
            }
            return new OrderDtos.CreateOrderRequest(orderType, null, SessionStore.get().getCurrentBranchId(), name.getText().trim(),
                    phone.getText() == null || phone.getText().isBlank() ? null : phone.getText().trim(), null);
        });

        dialog.showAndWait().ifPresent(this::createQuickOrder);
    }

    /** Best-effort single-match lookup - if the phone matches exactly one known customer, their
     * name is filled in automatically; otherwise the user just types the name themselves (no hard
     * failure either way, this is a convenience, not a requirement). */
    private void lookupCustomerByPhone(String phone, TextField nameField, Label statusLabel) {
        if (phone == null || phone.isBlank()) {
            statusLabel.setText("Enter a phone number first.");
            return;
        }
        String query = java.net.URLEncoder.encode(phone.trim(), java.nio.charset.StandardCharsets.UTF_8);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/customers?query=" + query);
                List<CustomerDtos.CustomerDto> matches = apiClient.convertList(data, CustomerDtos.CustomerDto.class);
                Platform.runLater(() -> {
                    if (matches.isEmpty()) {
                        statusLabel.setText("No matching customer - new guest.");
                    } else {
                        nameField.setText(matches.get(0).name());
                        statusLabel.setText("Found: " + matches.get(0).name()
                                + (matches.size() > 1 ? " (+" + (matches.size() - 1) + " more matches)" : ""));
                    }
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Lookup failed: " + ex.getMessage()));
            }
        }, "chefpay-customer-lookup");
        worker.setDaemon(true);
        worker.start();
    }

    private void createQuickOrder(OrderDtos.CreateOrderRequest payload) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/orders", payload);
                OrderDtos.OrderDto order = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> onTableOpened.accept(order));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not create order: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-quick-order-create");
        worker.setDaemon(true);
        worker.start();
    }

    /** RESERVED -> AVAILABLE is a plain status PATCH, same as a reservation itself - table-side
     * state only, no order involved (that's created later when the guest is actually seated). */
    private void setTableStatus(TableDto table, String newStatus, Runnable onSuccess) {
        var payload = new TableAdminDtos.UpdateTableRequest(null, null, null, newStatus, null, null, null, table.version());
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/tables/" + table.id(), payload);
                Platform.runLater(() -> {
                    reload();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not update " + table.name() + ": " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-table-status");
        worker.setDaemon(true);
        worker.start();
    }

    private void reserveTable(TableDto table) {
        setTableStatus(table, "RESERVED", null);
    }

    /** The table grid doesn't carry an order id directly - {@code RestaurantTable} doesn't store
     * a back-reference to its current order - so this looks it up the same way the rest of the
     * app already does: the open-orders list includes {@code tableId}, find the one that matches. */
    private void printBillForTable(TableDto table) {
        Thread worker = new Thread(() -> {
            try {
                var ordersData = apiClient.get("/api/orders");
                List<OrderDtos.OrderDto> openOrders = apiClient.convertList(ordersData, OrderDtos.OrderDto.class);
                OrderDtos.OrderDto order = openOrders.stream()
                        .filter(o -> table.id().equals(o.tableId()))
                        .findFirst()
                        .orElse(null);
                if (order == null) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                            table.name() + " has no open order to print right now.").showAndWait());
                    return;
                }
                var receiptData = apiClient.get("/api/billing/orders/" + order.id() + "/receipt");
                BillingDtos.ReceiptDto receipt = apiClient.convert(receiptData, BillingDtos.ReceiptDto.class);
                Platform.runLater(() -> ReceiptPrinter.show("Receipt - " + receipt.orderNumber(), receipt.text()));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not print bill: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-print-bill");
        worker.setDaemon(true);
        worker.start();
    }

    private void openTable(TableDto table) {
        if ("BLOCKED".equals(table.status())) {
            new Alert(Alert.AlertType.INFORMATION, table.name() + " is blocked.").showAndWait();
            return;
        }
        if ("RESERVED".equals(table.status())) {
            promptReservedTable(table);
            return;
        }
        openTableOrder(table);
    }

    /** A reserved table can't guest-seat straight into an order the way an AVAILABLE one does -
     * offer the two things that actually make sense for a table someone already pre-booked. */
    private void promptReservedTable(TableDto table) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Reserved table");
        alert.setHeaderText(table.name() + " is reserved for a guest.");
        alert.setContentText("Seat the guest now, or cancel the reservation to free the table back up?");
        ButtonType seatNow = new ButtonType("Seat Guest Now");
        ButtonType cancelReservation = new ButtonType("Cancel Reservation");
        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(seatNow, cancelReservation, close);
        alert.showAndWait().ifPresent(choice -> {
            if (choice == seatNow) {
                seatReservedTable(table);
            } else if (choice == cancelReservation) {
                setTableStatus(table, "AVAILABLE", null);
            }
        });
    }

    /** RESERVED can only move to OCCUPIED, AVAILABLE or BLOCKED (see the table entity's transition
     * rules) - it can't jump straight to ORDER_PLACED the way the server tries to once an item's
     * added to a fresh order, so seat the guest (flip to OCCUPIED) first and only then open the
     * order on it. */
    private void seatReservedTable(TableDto table) {
        setTableStatus(table, "OCCUPIED", () -> openTableOrder(table));
    }

    private void openTableOrder(TableDto table) {
        Thread worker = new Thread(() -> {
            try {
                var payload = new OrderDtos.CreateOrderRequest("DINE_IN", table.id(), null, null, null, null);
                var data = apiClient.post("/api/orders", payload);
                OrderDtos.OrderDto order = apiClient.convert(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> onTableOpened.accept(order));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not open table: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-open-table");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
