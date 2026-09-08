package com.chefpay.javafx.billing;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.StompWebSocketClient;
import com.chefpay.javafx.client.dto.BillingDtos;
import com.chefpay.javafx.client.dto.OrderDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.common.ReceiptPrinter;
import com.chefpay.javafx.common.UpiQrGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Billing screen (Phase 4, requirement's Tax/Discount/Billing/Split Bill/Payments/Receipt/Cash
 * Management). Deliberately does not re-implement the order lifecycle state machine - "Request
 * Bill" is the existing {@code PATCH /api/orders/{id}/status}, everything else calls the new
 * {@code /api/billing/*} endpoints and re-renders straight from the server's response, same
 * "no client-side money math" rule ({@code OrderTakingView}'s Javadoc, requirement §50/§61) that
 * every other screen in this app follows.
 *
 * <p>Action buttons are hidden (not just disabled) for permissions the logged-in user doesn't
 * hold, matching ARCHITECTURE.md §7 - "clients hide UI they can't use but the server is the only
 * trust boundary." The server re-checks every one of these regardless of what this screen shows.
 */
public class BillingView {

    private static final Set<String> BILLABLE_STATUSES = Set.of("SERVED", "BILL_REQUESTED", "BILLED", "PAYMENT_PENDING");

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final StompWebSocketClient wsClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ListView<OrderDtos.OrderDto> orderList = new ListView<>();
    private final VBox detailBox = new VBox(12);
    private final Label statusLabel = new Label();

    private OrderDtos.OrderDto selectedOrder;
    private BillingDtos.BillDto currentBill;
    /** Round 9: set by {@link #focusOrder}, consumed the next time {@link #renderOrderList} runs
     * (i.e. right after the {@code reload()} that same call triggers) to auto-select that one
     * order, so {@code ShellView}'s new "Bill Table" shortcut from {@code OrderTakingView} lands
     * directly on this order's bill instead of the generic "select one on the left" empty state. */
    private java.util.UUID pendingFocusOrderId;
    /** Cached active discount presets for {@code applyDiscountDialog}'s preset picker - refreshed
     * alongside the billable-orders list on every {@code reload()} so Settings-screen changes show
     * up here without a dedicated reload button. Empty/stale is harmless: the dialog just falls
     * back to its "Custom" ad-hoc entry, exactly like it always worked before presets existed. */
    private List<BillingDtos.DiscountDto> discountPresets = List.of();
    /** Cached restaurant config for {@code recordPaymentDialog}'s payment-method filter and its
     * "Show QR" UPI action - refreshed on every {@code reload()}, same non-fatal-if-stale reasoning
     * as {@code discountPresets} above (falls back to showing every {@code PaymentMethod} and
     * hiding the QR action until this loads). */
    private RestaurantDtos.RestaurantDto restaurantConfig;
    /** Round 11: guards every action below against a second click landing before the first
     * request's {@code Platform.runLater} callback has re-enabled the UI - this screen had reports
     * of a payment "needing 2-3 clicks", and unlike {@code OrderTakingView} (which at least
     * disabled its menu grid while a mutation was in flight) this screen had NO such guard on any
     * of its seven mutating actions before this. Disabling the whole {@link #detailBox} (rather
     * than one button) is deliberate: every action-row button here is rebuilt fresh by
     * {@link #renderBill} on every reload, so there's no stable button reference to hold onto
     * across a request - disabling the container they live in is what actually blocks a second
     * click app-wide, including each payment row's own "Void" button. */
    private boolean mutationInFlight = false;

    public BillingView(ApiClient apiClient, StompWebSocketClient wsClient) {
        this.apiClient = apiClient;
        this.wsClient = wsClient;

        root.setTop(buildHeader());
        root.setLeft(buildOrderList());
        root.setCenter(buildDetailPanel());

        wsClient.subscribe("/topic/orders", (dest, body) -> onOrderEvent(body));
        renderEmptyDetail("Select an order on the left to view its bill.");
    }

    private HBox buildHeader() {
        Label title = new Label("Billing");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        // Round 13 (AI Backbone Addendum F1.5) - logs a cash-drawer open not attached to any sale,
        // same BILLING_MANAGE permission as every other drawer-facing action on this screen, so
        // NO_SALE_FREQUENCY has real events to evaluate rather than never firing at all.
        if (SessionStore.get().hasPermission("BILLING_MANAGE")) {
            Button noSale = new Button("No Sale");
            noSale.setOnAction(e -> noSaleDialog());
            header.getChildren().add(header.getChildren().size() - 1, noSale);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    /** Round 13 - the "No Sale" drawer-open button (F1.5). Deliberately a lightweight confirm +
     * optional reason, not folded into {@link #mutationInFlight}'s bill-mutation guard - this action
     * has no bill/order selected and can't race with anything else on this screen. */
    private void noSaleDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("No Sale");
        dialog.setHeaderText("Open the cash drawer without a sale?");
        dialog.setContentText("Reason (optional):");
        dialog.showAndWait().ifPresent(reason -> {
            String trimmed = reason == null || reason.isBlank() ? null : reason.trim();
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/billing/no-sale", new BillingDtos.NoSaleRequest(trimmed));
                    Platform.runLater(() -> statusLabel.setText("No-sale drawer open recorded."));
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not record no-sale: " + ex.getMessage()).showAndWait());
                }
            }, "chefpay-no-sale");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private VBox buildOrderList() {
        orderList.setPrefWidth(300);
        orderList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(OrderDtos.OrderDto order, boolean empty) {
                super.updateItem(order, empty);
                if (empty || order == null) {
                    setText(null);
                } else {
                    String table = order.tableName() != null ? " • " + order.tableName() : "";
                    setText(order.orderNumber() + table + "\n" + order.status().replace('_', ' '));
                }
            }
        });
        orderList.getSelectionModel().selectedItemProperty().addListener((obs, old, order) -> {
            if (order != null) {
                loadBill(order);
            }
        });

        VBox box = new VBox(orderList);
        box.setPadding(new Insets(16, 0, 16, 16));
        VBox.setVgrow(orderList, Priority.ALWAYS);
        return box;
    }

    private ScrollPane buildDetailPanel() {
        detailBox.setPadding(new Insets(16, 24, 24, 24));
        ScrollPane scroll = new ScrollPane(detailBox);
        scroll.setFitToWidth(true);
        return scroll;
    }

    /** Call when this screen becomes visible. No background poll (unlike the always-on Kitchen
     * screen) - billing is a deliberate, one-cashier-at-a-time workflow; a manual Refresh plus the
     * WebSocket subscription above cover staleness without a screen nobody asked to keep live. */
    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/orders");
                List<OrderDtos.OrderDto> billable = apiClient.convertList(data, OrderDtos.OrderDto.class).stream()
                        .filter(o -> BILLABLE_STATUSES.contains(o.status()))
                        .toList();
                Platform.runLater(() -> renderOrderList(billable));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load orders: " + ex.getMessage()));
            }
        }, "chefpay-billing-load");
        worker.setDaemon(true);
        worker.start();
        loadDiscountPresets();
        loadRestaurantConfig();
    }

    private void loadRestaurantConfig() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                com.chefpay.javafx.client.LocalDatabase.putCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_RESTAURANT, data.toString());
                Platform.runLater(() -> this.restaurantConfig = restaurant);
            } catch (ApiException ex) {
                // Round 11: fall back to SyncEngine's last-cached config rather than always running
                // on the "nothing loaded yet" fallback (every PaymentMethod, no QR action) even when
                // a perfectly good cached value exists - see restaurantConfig's javadoc.
                com.chefpay.javafx.client.LocalDatabase.CachedPayload cached =
                        com.chefpay.javafx.client.LocalDatabase.readCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_RESTAURANT);
                if (cached != null) {
                    try {
                        RestaurantDtos.RestaurantDto restaurant = apiClient.convert(apiClient.parseCached(cached.payloadJson()), RestaurantDtos.RestaurantDto.class);
                        Platform.runLater(() -> this.restaurantConfig = restaurant);
                    } catch (RuntimeException parseEx) {
                        // Corrupt/unparseable cache entry - leave restaurantConfig null.
                    }
                }
            }
        }, "chefpay-restaurant-config-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadDiscountPresets() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/billing/discounts");
                List<BillingDtos.DiscountDto> presets = apiClient.convertList(data, BillingDtos.DiscountDto.class).stream()
                        .filter(BillingDtos.DiscountDto::active)
                        .toList();
                com.chefpay.javafx.client.LocalDatabase.putCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_DISCOUNTS, data.toString());
                Platform.runLater(() -> this.discountPresets = presets);
            } catch (ApiException ex) {
                // Bistrodesk Phase 10: fall back to SyncEngine's last-cached presets (same pattern as
                // loadRestaurantConfig's own fallback just above) rather than always landing on
                // "Custom only" the moment the server is briefly unreachable mid-shift - a genuine
                // permission gap (role lacks DISCOUNT_APPROVE/BILLING_MANAGE/RESTAURANT_MANAGE) and a
                // network outage both throw the same ApiException here, so this cache read is a no-op
                // (returns null) for the permission case and a real recovery for the offline case.
                com.chefpay.javafx.client.LocalDatabase.CachedPayload cached =
                        com.chefpay.javafx.client.LocalDatabase.readCached(com.chefpay.javafx.client.SyncEngine.CACHE_KEY_DISCOUNTS);
                if (cached != null) {
                    try {
                        List<BillingDtos.DiscountDto> presets = apiClient.convertList(apiClient.parseCached(cached.payloadJson()), BillingDtos.DiscountDto.class)
                                .stream().filter(BillingDtos.DiscountDto::active).toList();
                        Platform.runLater(() -> this.discountPresets = presets);
                    } catch (RuntimeException parseEx) {
                        // Corrupt/unparseable cache entry - leave discountPresets as whatever it was.
                    }
                }
            }
        }, "chefpay-discount-presets-load");
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 9: jump straight to one specific order's bill (used by {@code ShellView} when a
     * waiter/cashier taps "Bill Table" on {@code OrderTakingView} instead of coming here via the
     * nav and picking the table out of the list themselves). If the order isn't actually billable
     * yet (not in {@link #BILLABLE_STATUSES} - e.g. it hasn't been marked Served) it simply won't
     * be in the reloaded list and this becomes a no-op past the reload, same as if the cashier had
     * looked for it and not found it. */
    public void focusOrder(java.util.UUID orderId) {
        this.pendingFocusOrderId = orderId;
        reload();
    }

    private void renderOrderList(List<OrderDtos.OrderDto> orders) {
        statusLabel.setText(orders.size() + " order(s) awaiting billing");
        if (pendingFocusOrderId != null) {
            java.util.UUID target = pendingFocusOrderId;
            pendingFocusOrderId = null;
            orderList.getItems().setAll(orders);
            orders.stream().filter(o -> o.id().equals(target)).findFirst()
                    .ifPresent(o -> orderList.getSelectionModel().select(o));
            return;
        }
        String selectedId = selectedOrder == null ? null : selectedOrder.id().toString();
        orderList.getItems().setAll(orders);
        if (selectedId == null) {
            return;
        }
        orders.stream().filter(o -> o.id().toString().equals(selectedId)).findFirst()
                .ifPresentOrElse(o -> orderList.getSelectionModel().select(o), () -> {
                    // The selected order dropped off the billable list (PAID/CLOSED/CANCELLED
                    // aren't in BILLABLE_STATUSES). If it just finished paying, the detail panel
                    // showing that bill is still exactly right - the receipt still needs to be
                    // viewed/printed - so keep it up rather than yanking it out from under the
                    // cashier the moment the very payment they just recorded lands. This was also
                    // the root cause of "View Receipt" throwing a NullPointerException: this reload
                    // (triggered automatically right after a successful payment) used to null out
                    // selectedOrder here, and if the cashier clicked View Receipt in that narrow
                    // window before the wipe, viewReceipt()'s worker thread read a null
                    // selectedOrder. Only clear the panel when the order left the list for some
                    // other reason (voided/changed on another terminal).
                    if (currentBill == null || !"PAID".equals(currentBill.orderStatus())) {
                        selectedOrder = null;
                        renderEmptyDetail("That order is no longer awaiting billing. Select another from the left.");
                    }
                });
    }

    private void loadBill(OrderDtos.OrderDto order) {
        this.selectedOrder = order;
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/billing/orders/" + order.id());
                BillingDtos.BillDto bill = apiClient.convert(data, BillingDtos.BillDto.class);
                Platform.runLater(() -> renderBill(bill));
            } catch (ApiException ex) {
                Platform.runLater(() -> renderEmptyDetail("Could not load bill: " + ex.getMessage()));
            }
        }, "chefpay-bill-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderEmptyDetail(String message) {
        currentBill = null;
        detailBox.getChildren().setAll(new Label(message));
    }

    private void renderBill(BillingDtos.BillDto bill) {
        this.currentBill = bill;
        detailBox.getChildren().clear();

        Label header = new Label(bill.orderNumber() + "  •  " + bill.orderStatus().replace('_', ' '));
        header.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        detailBox.getChildren().add(header);

        detailBox.getChildren().add(moneyRow("Subtotal", bill.subtotal(), false));
        if (bill.discountAmount().compareTo(BigDecimal.ZERO) > 0) {
            String label = "Discount" + (bill.discountReason() != null ? " (" + bill.discountReason() + ")" : "");
            detailBox.getChildren().add(moneyRow(label, bill.discountAmount().negate(), false));
        }
        for (BillingDtos.TaxLineDto tax : bill.taxLines()) {
            detailBox.getChildren().add(moneyRow(tax.name() + " (" + tax.ratePercent() + "%)", tax.amount(), false));
        }
        if (bill.serviceChargeAmount().compareTo(BigDecimal.ZERO) > 0) {
            detailBox.getChildren().add(moneyRow("Service Charge", bill.serviceChargeAmount(), false));
        }
        if (bill.tipAmount().compareTo(BigDecimal.ZERO) > 0) {
            detailBox.getChildren().add(moneyRow("Tip", bill.tipAmount(), false));
        }
        detailBox.getChildren().add(new Separator());
        detailBox.getChildren().add(moneyRow("TOTAL", bill.totalAmount(), true));
        detailBox.getChildren().add(moneyRow("Paid", bill.amountPaid(), false));
        detailBox.getChildren().add(moneyRow("Balance Due", bill.balanceDue(), true));

        if (!bill.payments().isEmpty()) {
            detailBox.getChildren().add(new Separator());
            Label paymentsHeader = new Label("Payments");
            paymentsHeader.setStyle("-fx-font-weight: bold;");
            detailBox.getChildren().add(paymentsHeader);
            for (BillingDtos.PaymentDto payment : bill.payments()) {
                detailBox.getChildren().add(buildPaymentRow(payment));
            }
        }

        detailBox.getChildren().add(new Separator());
        detailBox.getChildren().add(buildActionRow(bill));
    }

    private HBox moneyRow(String label, BigDecimal amount, boolean emphasize) {
        Label l = new Label(label);
        Label v = new Label("₹" + amount.setScale(2, java.math.RoundingMode.HALF_UP));
        if (emphasize) {
            l.setStyle("-fx-font-weight: bold;");
            v.setStyle("-fx-font-weight: bold;");
        }
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, l, spacer, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox buildPaymentRow(BillingDtos.PaymentDto payment) {
        String text = payment.method() + "  ₹" + payment.amount() + "  (" + payment.receiptNumber() + ")"
                + (payment.voided() ? "  [VOIDED]" : "");
        Label label = new Label(text);
        if (payment.voided()) {
            label.setStyle("-fx-text-fill: #999; -fx-strikethrough: true;");
        }
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, label, spacer);
        row.setAlignment(Pos.CENTER_LEFT);

        boolean canVoid = !payment.voided() && SessionStore.get().hasPermission("BILLING_MANAGE")
                && ("BILLED".equals(currentBill.orderStatus()) || "PAYMENT_PENDING".equals(currentBill.orderStatus()));
        if (canVoid) {
            Button voidBtn = new Button("Void");
            voidBtn.setOnAction(e -> voidPayment(payment));
            row.getChildren().add(voidBtn);
        }
        return row;
    }

    private FlowPane buildActionRow(BillingDtos.BillDto bill) {
        FlowPane actions = new FlowPane(10, 10);

        if ("SERVED".equals(bill.orderStatus()) || "BILL_REQUESTED".equals(bill.orderStatus())) {
            if (SessionStore.get().hasPermission("DISCOUNT_APPROVE")) {
                Button discount = new Button("Apply Discount");
                discount.setOnAction(e -> applyDiscountDialog());
                actions.getChildren().add(discount);
            }
        }

        // Round 12 §8 - the old two-click "Request Bill" then "Generate Bill" is now a single
        // action from Served (the requirement's "remove the unnecessary Request Bill step"). The
        // BILL_REQUESTED intermediate status still exists server-side (nothing removed there, so
        // an order that somehow got stuck there from before this change - or a concurrent update -
        // still shows a working "Generate Bill" of its own) - it's just no longer a state the
        // cashier has to click through manually.
        if ("SERVED".equals(bill.orderStatus()) && SessionStore.get().hasPermission("BILLING_MANAGE")) {
            Button generate = new Button("Generate Bill");
            generate.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            generate.setOnAction(e -> generateBillFromServed());
            actions.getChildren().add(generate);
        }
        if ("BILL_REQUESTED".equals(bill.orderStatus()) && SessionStore.get().hasPermission("BILLING_MANAGE")) {
            Button generate = new Button("Generate Bill");
            generate.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            generate.setOnAction(e -> generateBill());
            actions.getChildren().add(generate);
        }

        // Round 12 §11 - "Back" to Served, only while it's still risk-free (no payment recorded
        // yet - see BillingService#backToServed's javadoc for exactly why that's the line). Once a
        // payment exists this button simply isn't offered; Void Payment (below) is the correct
        // undo path from that point on.
        if (("BILLED".equals(bill.orderStatus()) || "BILL_REQUESTED".equals(bill.orderStatus()))
                && bill.payments().isEmpty() && SessionStore.get().hasPermission("BILLING_MANAGE")) {
            Button back = new Button("◂ Back");
            back.setOnAction(e -> backToServed());
            actions.getChildren().add(back);
        }

        if (("BILLED".equals(bill.orderStatus()) || "PAYMENT_PENDING".equals(bill.orderStatus()))
                && SessionStore.get().hasPermission("BILLING_MANAGE")) {
            Button pay = new Button("Record Payment");
            pay.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
            pay.setOnAction(e -> recordPaymentDialog());
            actions.getChildren().add(pay);

            Button split = new Button("Split Evenly");
            split.setOnAction(e -> splitBillDialog());
            actions.getChildren().add(split);
        }

        if (bill.payments().stream().anyMatch(p -> !p.voided()) && SessionStore.get().hasPermission("BILLING_MANAGE")) {
            Button receipt = new Button("View Receipt");
            receipt.setOnAction(e -> viewReceipt());
            actions.getChildren().add(receipt);

            // Round 11 - "give whatsapp and email receipt option."
            Button emailReceipt = new Button("Email Receipt");
            emailReceipt.setOnAction(e -> emailReceiptDialog());
            actions.getChildren().add(emailReceipt);

            Button whatsAppReceipt = new Button("WhatsApp Receipt");
            whatsAppReceipt.setOnAction(e -> whatsAppReceiptDialog());
            actions.getChildren().add(whatsAppReceipt);
        }

        return actions;
    }

    // ---- actions ----

    private void setMutationInFlight(boolean inFlight) {
        this.mutationInFlight = inFlight;
        detailBox.setDisable(inFlight);
    }

    /** Round 12 §8 - one click, straight from Served: chains the same {@code PATCH .../status}
     * (SERVED -> BILL_REQUESTED) this screen always used, followed immediately by the existing
     * {@code POST .../generate} (BILL_REQUESTED -> BILLED), so the cashier experiences a single
     * "Generate Bill" action instead of two. Neither server endpoint changed - {@code
     * OrderStatus#canTransitionTo} still requires passing through BILL_REQUESTED, this view just no
     * longer makes the cashier click for it separately. Shares the same confirmation-prompt gate as
     * {@link #generateBill} (Round 12 §9). */
    private void generateBillFromServed() {
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        confirmGenerateBillIfConfigured(() -> {
            if (mutationInFlight) {
                return;
            }
            setMutationInFlight(true);
            var request = new OrderDtos.UpdateOrderStatusRequest("BILL_REQUESTED", null, order.version());
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.patch("/api/orders/" + order.id() + "/status", request);
                    OrderDtos.OrderDto requested = apiClient.convert(data, OrderDtos.OrderDto.class);
                    var billData = apiClient.post("/api/billing/orders/" + order.id() + "/generate?version=" + requested.version(), null);
                    BillingDtos.BillDto bill = apiClient.convert(billData, BillingDtos.BillDto.class);
                    Platform.runLater(() -> {
                        setMutationInFlight(false);
                        this.selectedOrder = requested;
                        updateOrderVersion(bill.orderVersion());
                        renderBill(bill);
                        reload();
                    });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
                }
            }, "chefpay-generate-bill-from-served");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /** Round 12 §11 - undo a too-early Generate Bill, back to Served (only offered by {@link
     * #buildActionRow} while it's still risk-free - see {@code BillingService#backToServed}'s
     * javadoc). Discount amount/reason are untouched by the server call; a fresh Apply Discount or
     * Generate Bill from here simply picks up where the cashier left off. */
    private void backToServed() {
        if (mutationInFlight) {
            return;
        }
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        setMutationInFlight(true);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/billing/orders/" + order.id() + "/back?version=" + order.version(), null);
                BillingDtos.BillDto bill = apiClient.convert(data, BillingDtos.BillDto.class);
                Platform.runLater(() -> { setMutationInFlight(false); updateOrderVersion(bill.orderVersion()); renderBill(bill); reload(); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
            }
        }, "chefpay-bill-back");
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 12 §9 - the "discounts can't be changed afterward" warning is now configurable
     * (Settings > Billing Workflow); when turned off, both callers below proceed immediately.
     * Defaults to showing it (matches every other place in this app where a stale/not-yet-loaded
     * {@code restaurantConfig} falls back to today's existing behavior rather than a new one). */
    private void confirmGenerateBillIfConfigured(Runnable proceed) {
        boolean showConfirmation = restaurantConfig == null || restaurantConfig.showDiscountConfirmation();
        if (!showConfirmation) {
            proceed.run();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Generate the final bill? Discounts can't be changed afterward.");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> proceed.run());
    }

    private void applyDiscountDialog() {
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        Dialog<BillingDtos.ApplyDiscountRequest> dialog = new Dialog<>();
        dialog.setTitle("Apply Discount");
        ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CANCEL);

        // Preset picker - populated from Settings' Discount Presets (cached in discountPresets by
        // reload()). "Custom" keeps the original ad-hoc type+value entry this dialog always had;
        // picking a real preset instead sends discountId and leaves type/value null, per
        // ApplyDiscountRequest's "exactly one of discountId or (type + value)" contract.
        String customOption = "Custom (enter value below)";
        ChoiceBox<String> preset = new ChoiceBox<>();
        preset.getItems().add(customOption);
        for (BillingDtos.DiscountDto d : discountPresets) {
            preset.getItems().add(d.name());
        }
        preset.getSelectionModel().selectFirst();

        ChoiceBox<String> type = new ChoiceBox<>(javafx.collections.FXCollections.observableArrayList("PERCENTAGE", "FIXED_AMOUNT"));
        type.getSelectionModel().selectFirst();
        TextField value = new TextField();
        value.setPromptText("e.g. 10 for 10% or a flat amount");
        TextField reason = new TextField();
        reason.setPromptText("Reason (required for the audit trail)");

        preset.getSelectionModel().selectedItemProperty().addListener((obs, old, selectedName) -> {
            boolean custom = selectedName == null || customOption.equals(selectedName);
            type.setDisable(!custom);
            value.setDisable(!custom);
            if (!custom) {
                discountPresets.stream().filter(d -> d.name().equals(selectedName)).findFirst().ifPresent(d -> {
                    type.setValue(d.type());
                    value.setText(d.value().toPlainString());
                    if (reason.getText().isBlank()) {
                        reason.setText(d.name());
                    }
                });
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Preset"), preset);
        grid.addRow(1, new Label("Type"), type);
        grid.addRow(2, new Label("Value"), value);
        grid.addRow(3, new Label("Reason"), reason);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != applyType) {
                return null;
            }
            String selectedName = preset.getValue();
            boolean custom = selectedName == null || customOption.equals(selectedName);
            if (!custom) {
                var matched = discountPresets.stream().filter(d -> d.name().equals(selectedName)).findFirst();
                if (matched.isEmpty()) {
                    new Alert(Alert.AlertType.ERROR, "That preset is no longer available - pick another or use Custom.").showAndWait();
                    return null;
                }
                return new BillingDtos.ApplyDiscountRequest(matched.get().id(), null, null, reason.getText(), order.version());
            }
            try {
                BigDecimal v = new BigDecimal(value.getText().trim());
                return new BillingDtos.ApplyDiscountRequest(null, type.getValue(), v, reason.getText(), order.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid number for the discount value.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            if (mutationInFlight) {
                return;
            }
            setMutationInFlight(true);
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.post("/api/billing/orders/" + order.id() + "/discount", request);
                    BillingDtos.BillDto bill = apiClient.convert(data, BillingDtos.BillDto.class);
                    Platform.runLater(() -> { setMutationInFlight(false); updateOrderVersion(bill.orderVersion()); renderBill(bill); });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
                }
            }, "chefpay-apply-discount");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void generateBill() {
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        confirmGenerateBillIfConfigured(() -> {
            if (mutationInFlight) {
                return;
            }
            setMutationInFlight(true);
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.post("/api/billing/orders/" + order.id() + "/generate?version=" + order.version(), null);
                    BillingDtos.BillDto bill = apiClient.convert(data, BillingDtos.BillDto.class);
                    Platform.runLater(() -> { setMutationInFlight(false); updateOrderVersion(bill.orderVersion()); renderBill(bill); reload(); });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
                }
            }, "chefpay-generate-bill");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void recordPaymentDialog() {
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        Dialog<BillingDtos.RecordPaymentRequest> dialog = new Dialog<>();
        dialog.setTitle("Record Payment");
        ButtonType payType = new ButtonType("Record", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(payType, ButtonType.CANCEL);

        // Filtered to what Settings' Payment Methods panel currently has enabled - falls back to
        // every PaymentMethod if restaurantConfig hasn't loaded yet (never blocks recording a
        // payment just because this cache is cold).
        List<String> allMethods = List.of("CASH", "CARD", "UPI", "WALLET", "OTHER");
        List<String> enabledMethods = restaurantConfig == null || restaurantConfig.enabledPaymentMethods() == null
                || restaurantConfig.enabledPaymentMethods().isBlank()
                ? allMethods
                : allMethods.stream().filter(m -> restaurantConfig.enabledPaymentMethods().contains(m)).toList();
        ChoiceBox<String> method = new ChoiceBox<>(javafx.collections.FXCollections.observableArrayList(
                enabledMethods.isEmpty() ? allMethods : enabledMethods));
        method.getSelectionModel().selectFirst();
        Label amountLabel = new Label("Tendered Amount");
        // Pre-filled with the balance due (the overwhelmingly common case - guest hands over exact
        // change, or the cashier just confirms it) but still a plain editable TextField, so a
        // partial/split/over-tendered amount is just a matter of typing over the default instead of
        // every payment requiring the cashier to type the full amount from scratch.
        TextField amountField = new TextField(currentBill.balanceDue().toPlainString());
        amountField.selectAll();

        // "Show QR" - only meaningful for UPI, and only once a VPA is configured (Settings' UPI/QR
        // Payments panel). See UpiQrGenerator's javadoc for exactly what scanning this QR does (and
        // doesn't do - no automatic payment confirmation).
        Button showQr = new Button("Show QR");
        showQr.setVisible(false);
        showQr.setManaged(false);
        showQr.setOnAction(e -> showUpiQrDialog(order, amountField.getText()));
        method.getSelectionModel().selectedItemProperty().addListener((obs, old, m) -> {
            amountLabel.setText("CASH".equals(m) ? "Tendered Amount" : "Amount");
            boolean upi = "UPI".equals(m);
            showQr.setVisible(upi);
            showQr.setManaged(upi);
        });
        TextField reference = new TextField();
        reference.setPromptText("Card/UPI reference (optional)");
        TextField tip = new TextField();
        tip.setPromptText("Tip (optional)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Method"), method, showQr);
        grid.addRow(1, amountLabel, amountField);
        grid.addRow(2, new Label("Reference"), reference);
        grid.addRow(3, new Label("Tip"), tip);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != payType) {
                return null;
            }
            try {
                BigDecimal amountOrTendered = amountField.getText().isBlank() ? null : new BigDecimal(amountField.getText().trim());
                BigDecimal tipAmount = tip.getText().isBlank() ? null : new BigDecimal(tip.getText().trim());
                boolean cash = "CASH".equals(method.getValue());
                return new BillingDtos.RecordPaymentRequest(method.getValue(),
                        cash ? null : amountOrTendered, cash ? amountOrTendered : null,
                        reference.getText().isBlank() ? null : reference.getText(), tipAmount, order.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter valid numbers for amount/tip.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            if (mutationInFlight) {
                return;
            }
            setMutationInFlight(true);
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.post("/api/billing/orders/" + order.id() + "/payments", request);
                    BillingDtos.BillDto bill = apiClient.convert(data, BillingDtos.BillDto.class);
                    // Best-effort cash drawer kick on a cash sale - see Restaurant.cashDrawerEnabled's
                    // javadoc for exactly how reliable this is. Deliberately silent on failure (no
                    // alert) - a POS shouldn't interrupt the payment flow to complain the drawer
                    // didn't ping; CashManagementView's manual "Open Drawer" button covers the case
                    // where staff needs to check/retry.
                    if ("CASH".equals(request.method()) && restaurantConfig != null && restaurantConfig.cashDrawerEnabled()) {
                        ReceiptPrinter.openCashDrawer(restaurantConfig.receiptPrinterName());
                    }
                    Platform.runLater(() -> {
                        setMutationInFlight(false);
                        updateOrderVersion(bill.orderVersion());
                        renderBill(bill);
                        reload();
                        BillingDtos.PaymentDto last = bill.payments().isEmpty() ? null : bill.payments().get(bill.payments().size() - 1);
                        if (last != null && last.changeAmount() != null && last.changeAmount().compareTo(BigDecimal.ZERO) > 0) {
                            new Alert(Alert.AlertType.INFORMATION, "Change due: ₹" + last.changeAmount()).showAndWait();
                        }
                        maybeAutoPrintReceipt(order.id(), bill);
                    });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
                }
            }, "chefpay-record-payment");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /** Round 11: "once bill paid and cash collected, print [receipt] directly" - the configurable
     * replacement for always making the cashier click "View Receipt" first. Fires right after a
     * payment is recorded, only when ALL of the following hold: the bill's balance due has reached
     * zero (a partial/split payment leaves this screen exactly as it was - the cashier still needs
     * to come back for the rest), {@code Restaurant.autoPrintReceiptOnPayment} is on, and a
     * {@code receiptPrinterName} is actually configured. On any other case - including the printer
     * being unreachable or the silent print itself failing - this is a silent no-op and the
     * existing "View Receipt" button (still shown by {@link #buildActionRow} whenever there's a
     * non-voided payment) remains the fallback, so a receipt is never lost, only ever not
     * auto-printed. */
    private void maybeAutoPrintReceipt(java.util.UUID orderId, BillingDtos.BillDto bill) {
        if (bill.balanceDue().compareTo(BigDecimal.ZERO) > 0) {
            return;
        }
        if (restaurantConfig == null || !restaurantConfig.autoPrintReceiptOnPayment()) {
            return;
        }
        String printerName = restaurantConfig.receiptPrinterName();
        if (printerName == null || printerName.isBlank()) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/billing/orders/" + orderId + "/receipt");
                BillingDtos.ReceiptDto receipt = apiClient.convert(data, BillingDtos.ReceiptDto.class);
                ReceiptPrinter.printSilently(printerName, receipt.text());
            } catch (ApiException ignored) {
                // Non-fatal, matching every other silent-print call in this app - the "View Receipt"
                // button covers it if this quietly doesn't happen.
            }
        }, "chefpay-auto-print-receipt");
        worker.setDaemon(true);
        worker.start();
    }

    /** Shows a UPI "scan to pay" QR for the amount currently typed into the payment dialog
     * (falling back to the order's balance due if that field is blank/invalid) - see
     * {@code UpiQrGenerator}'s javadoc for exactly what this does and doesn't guarantee. */
    private void showUpiQrDialog(OrderDtos.OrderDto order, String typedAmount) {
        if (restaurantConfig == null || restaurantConfig.upiVpaId() == null || restaurantConfig.upiVpaId().isBlank()) {
            new Alert(Alert.AlertType.WARNING, "No UPI ID is configured yet - set one under Settings > "
                    + "UPI / QR Payments first.").showAndWait();
            return;
        }
        BigDecimal amount;
        try {
            amount = (typedAmount == null || typedAmount.isBlank()) ? currentBill.balanceDue() : new BigDecimal(typedAmount.trim());
        } catch (NumberFormatException ex) {
            amount = currentBill.balanceDue();
        }
        String payeeName = restaurantConfig.upiPayeeName() == null || restaurantConfig.upiPayeeName().isBlank()
                ? restaurantConfig.name() : restaurantConfig.upiPayeeName();
        String uri = UpiQrGenerator.buildUpiUri(restaurantConfig.upiVpaId(), payeeName, amount,
                "Order " + order.orderNumber(), order.orderNumber());

        javafx.scene.image.ImageView imageView;
        try {
            imageView = new javafx.scene.image.ImageView(UpiQrGenerator.generate(uri, 280));
        } catch (IllegalStateException ex) {
            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            return;
        }

        Label amountLabel = new Label("₹" + amount.setScale(2, java.math.RoundingMode.HALF_UP));
        amountLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label vpaLabel = new Label(restaurantConfig.upiVpaId());
        vpaLabel.setStyle("-fx-text-fill: #666;");
        Label help = new Label("Scan with any UPI app - PhonePe, Google Pay, Paytm, BHIM, or your bank's app.");
        help.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        help.setWrapText(true);
        help.setMaxWidth(280);

        VBox content = new VBox(10, imageView, amountLabel, vpaLabel, help);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(16));

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("UPI Payment - " + order.orderNumber());
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(content);
        alert.getButtonTypes().setAll(ButtonType.CLOSE);
        alert.showAndWait();
    }

    private void voidPayment(BillingDtos.PaymentDto payment) {
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Void Payment");
        dialog.setHeaderText("Void " + payment.method() + " payment of ₹" + payment.amount() + "?");
        dialog.setContentText("Reason:");
        dialog.showAndWait().ifPresent(reason -> {
            if (mutationInFlight) {
                return;
            }
            setMutationInFlight(true);
            var request = new BillingDtos.VoidPaymentRequest(reason, order.version());
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.post("/api/billing/orders/" + order.id() + "/payments/" + payment.id() + "/void", request);
                    BillingDtos.BillDto bill = apiClient.convert(data, BillingDtos.BillDto.class);
                    Platform.runLater(() -> { setMutationInFlight(false); updateOrderVersion(bill.orderVersion()); renderBill(bill); });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
                }
            }, "chefpay-void-payment");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void splitBillDialog() {
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog("2");
        dialog.setTitle("Split Bill Evenly");
        dialog.setHeaderText("Split the total of ₹" + currentBill.totalAmount() + " into how many shares?");
        dialog.setContentText("Ways:");
        dialog.showAndWait().ifPresent(waysText -> {
            if (mutationInFlight) {
                return;
            }
            int ways;
            try {
                ways = Integer.parseInt(waysText.trim());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a whole number of at least 2.").showAndWait();
                return;
            }
            setMutationInFlight(true);
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.get("/api/billing/orders/" + order.id() + "/split?ways=" + ways);
                    BillingDtos.SplitBillResponse split = apiClient.convert(data, BillingDtos.SplitBillResponse.class);
                    Platform.runLater(() -> {
                        setMutationInFlight(false);
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < split.shares().size(); i++) {
                            sb.append("Guest ").append(i + 1).append(": ₹").append(split.shares().get(i)).append('\n');
                        }
                        new Alert(Alert.AlertType.INFORMATION, sb.toString()).showAndWait();
                    });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
                }
            }, "chefpay-split-bill");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void viewReceipt() {
        // Captured here, on the FX thread, at the moment the button was actually clicked - not
        // read lazily from the field inside the worker below. `selectedOrder` can be nulled out by
        // a concurrent reload() (see renderOrderList's javadoc above for exactly this race) between
        // the click and the worker thread running; reading `this.selectedOrder` inside the lambda
        // was exactly what threw the NullPointerException reported against this button.
        if (mutationInFlight) {
            return;
        }
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        setMutationInFlight(true);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/billing/orders/" + order.id() + "/receipt");
                BillingDtos.ReceiptDto receipt = apiClient.convert(data, BillingDtos.ReceiptDto.class);
                Platform.runLater(() -> { setMutationInFlight(false); ReceiptPrinter.show("Receipt - " + receipt.orderNumber(), receipt.text()); });
            } catch (ApiException ex) {
                Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
            }
        }, "chefpay-view-receipt");
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 11 - "email receipt option". Prompts for a recipient address (no field on {@code
     * Order} caches one, so this always starts blank), fetches the receipt text fresh from the
     * server (same source {@link #viewReceipt} uses), and posts it to the new {@code
     * /api/billing/orders/{id}/receipt/email} endpoint. Server-side failures (SMTP not configured,
     * bad address, send failure) surface through the normal {@link #handleError} path with their
     * own clear message - see {@code EmailReceiptService}'s javadoc for exactly what each one means. */
    private void emailReceiptDialog() {
        if (mutationInFlight) {
            return;
        }
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Email Receipt");
        dialog.setHeaderText("Email the receipt for " + order.orderNumber());
        dialog.setContentText("Recipient email:");
        dialog.showAndWait().map(String::trim).filter(email -> !email.isBlank()).ifPresent(email -> {
            setMutationInFlight(true);
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/billing/orders/" + order.id() + "/receipt/email",
                            new BillingDtos.EmailReceiptRequest(email));
                    Platform.runLater(() -> {
                        setMutationInFlight(false);
                        new Alert(Alert.AlertType.INFORMATION, "Receipt emailed to " + email + ".").showAndWait();
                    });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
                }
            }, "chefpay-email-receipt");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /** Round 11 - "give whatsapp ... receipt option". No server round trip needed beyond
     * re-fetching the receipt text - see {@code WhatsAppSender}'s javadoc for exactly what "send"
     * means here (opens a pre-filled wa.me chat, doesn't push the message itself). Pre-fills the
     * phone field from {@code Order.customerPhone()} when the order has one, since that's already
     * exactly the number a receipt should go to. */
    private void whatsAppReceiptDialog() {
        if (mutationInFlight) {
            return;
        }
        OrderDtos.OrderDto order = selectedOrder;
        if (order == null) {
            return;
        }
        if (!com.chefpay.javafx.common.WhatsAppSender.canSendVia()) {
            new Alert(Alert.AlertType.WARNING, "This computer has no default browser/WhatsApp handler configured, "
                    + "so a WhatsApp message can't be opened from here.").showAndWait();
            return;
        }
        TextInputDialog dialog = new TextInputDialog(order.customerPhone() == null ? "" : order.customerPhone());
        dialog.setTitle("WhatsApp Receipt");
        dialog.setHeaderText("Send the receipt for " + order.orderNumber() + " via WhatsApp");
        dialog.setContentText("Customer phone (with country code, e.g. 91XXXXXXXXXX):");
        dialog.showAndWait().map(String::trim).filter(phone -> !phone.isBlank()).ifPresent(phone -> {
            setMutationInFlight(true);
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.get("/api/billing/orders/" + order.id() + "/receipt");
                    BillingDtos.ReceiptDto receipt = apiClient.convert(data, BillingDtos.ReceiptDto.class);
                    Platform.runLater(() -> {
                        setMutationInFlight(false);
                        boolean opened = com.chefpay.javafx.common.WhatsAppSender.send(phone, receipt.text());
                        if (!opened) {
                            new Alert(Alert.AlertType.WARNING, "Could not open WhatsApp for that number - double-check "
                                    + "it includes the country code with no spaces or symbols.").showAndWait();
                        }
                    });
                } catch (ApiException ex) {
                    Platform.runLater(() -> { setMutationInFlight(false); handleError(ex); });
                }
            }, "chefpay-whatsapp-receipt");
            worker.setDaemon(true);
            worker.start();
        });
    }

    // ---- helpers ----

    /** Every action here re-reads {@code selectedOrder.version()} for its next call - keep it in
     * sync with whatever the server just returned rather than waiting on a full order refetch. */
    private void updateOrderVersion(long newVersion) {
        if (selectedOrder != null) {
            selectedOrder = new OrderDtos.OrderDto(selectedOrder.id(), selectedOrder.orderNumber(), selectedOrder.orderType(),
                    selectedOrder.tableId(), selectedOrder.tableName(), selectedOrder.customerName(), selectedOrder.customerPhone(),
                    selectedOrder.waiterName(), selectedOrder.cashierName(), currentBill == null ? selectedOrder.status() : currentBill.orderStatus(),
                    currentBill == null ? selectedOrder.paymentStatus() : currentBill.paymentStatus(), selectedOrder.priority(),
                    selectedOrder.items(), selectedOrder.subtotal(), selectedOrder.discountAmount(), selectedOrder.taxAmount(),
                    selectedOrder.serviceChargeAmount(), selectedOrder.tipAmount(), selectedOrder.totalAmount(), selectedOrder.notes(),
                    selectedOrder.createdAt(), selectedOrder.updatedAt(), newVersion,
                    selectedOrder.deliveryBoyId(), selectedOrder.deliveryBoyName());
        }
    }

    private void handleError(ApiException ex) {
        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
            if (selectedOrder != null) {
                loadBill(selectedOrder);
            }
            new Alert(Alert.AlertType.WARNING, "This order changed elsewhere - showing the latest version. Please retry.").showAndWait();
        } else {
            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
        }
    }

    private void onOrderEvent(String rawJson) {
        try {
            JsonNode node = mapper.readTree(rawJson);
            String entityId = node.path("entityId").asText(null);
            if (entityId != null && selectedOrder != null && entityId.equals(selectedOrder.id().toString())) {
                loadBill(selectedOrder);
            }
        } catch (Exception ignored) {
            // malformed/unrelated event - ignore, the next event or a manual refresh will resync
        }
    }

    public Parent view() {
        return root;
    }
}
