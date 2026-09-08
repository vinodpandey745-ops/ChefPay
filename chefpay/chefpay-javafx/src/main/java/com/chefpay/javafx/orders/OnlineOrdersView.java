package com.chefpay.javafx.orders;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.StompWebSocketClient;
import com.chefpay.javafx.client.dto.DeliveryBoyDtos;
import com.chefpay.javafx.client.dto.OrderDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.common.ReceiptPrinter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * "It should appear under Online Orders" - a dedicated screen listing every currently-open order
 * with {@code orderType == ONLINE_ORDER}, newest first, so front-of-house has one place to see
 * what's come in from an outside channel without hunting for it on the Table View (which an
 * ONLINE_ORDER has no table on, since it's not a dine-in order). Today "online order" means
 * whatever staff logs by hand via {@code TableMatrixView}'s "+ Online Order" quick-order button -
 * there is no live Zomato/Swiggy feed yet (see {@code Restaurant.onlineOrderZomatoEnabled}'s
 * javadoc and ARCHITECTURE.md §13b); once that integration exists, it would create orders the same
 * way (via {@code OrderService.openOrCreateOrder} tagged {@code ONLINE_ORDER}) and they'd show up
 * here identically, no change needed to this screen.
 *
 * <p>{@code ShellView} separately auto-prints a KOT the instant one of these orders'
 * {@code ORDER_CREATED} event arrives, if {@code Restaurant.autoPrintOnlineOrders} is on - this
 * screen's own "Print KOT" button is for manual reprints/review, not the primary trigger.
 */
public class OnlineOrdersView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final Consumer<OrderDtos.OrderDto> onOrderOpened;
    private final VBox orderRows = new VBox(8);
    private final Label statusLabel = new Label();

    private RestaurantDtos.RestaurantDto restaurantConfig;
    private List<DeliveryBoyDtos.DeliveryBoyDto> deliveryBoys = List.of();
    private static final String UNASSIGNED = "Unassigned";

    public OnlineOrdersView(ApiClient apiClient, StompWebSocketClient wsClient, Consumer<OrderDtos.OrderDto> onOrderOpened) {
        this.apiClient = apiClient;
        this.onOrderOpened = onOrderOpened;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(orderRows);
        scroll.setFitToWidth(true);
        orderRows.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);

        wsClient.subscribe("/topic/orders", (dest, body) -> reload());
    }

    private HBox buildHeader() {
        Label title = new Label("Online Orders");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var ordersData = apiClient.get("/api/orders");
                List<OrderDtos.OrderDto> online = apiClient.convertList(ordersData, OrderDtos.OrderDto.class).stream()
                        .filter(o -> "ONLINE_ORDER".equals(o.orderType()))
                        .sorted(Comparator.comparing(OrderDtos.OrderDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                        .toList();

                var restaurantData = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(restaurantData, RestaurantDtos.RestaurantDto.class);

                List<DeliveryBoyDtos.DeliveryBoyDto> activeDeliveryBoys = List.of();
                if (restaurant.deliveryBoyFeatureEnabled()) {
                    try {
                        var deliveryBoyData = apiClient.get("/api/delivery-boys");
                        activeDeliveryBoys = apiClient.convertList(deliveryBoyData, DeliveryBoyDtos.DeliveryBoyDto.class).stream()
                                .filter(DeliveryBoyDtos.DeliveryBoyDto::active)
                                .toList();
                    } catch (ApiException ignored) {
                        // No permission to read the roster (or none configured yet) - fall back to
                        // showing the row's currently-assigned name with no picker, same as the
                        // toggle being off.
                    }
                }
                List<DeliveryBoyDtos.DeliveryBoyDto> resolvedDeliveryBoys = activeDeliveryBoys;

                Platform.runLater(() -> {
                    this.restaurantConfig = restaurant;
                    this.deliveryBoys = resolvedDeliveryBoys;
                    render(online);
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load online orders: " + ex.getMessage()));
            }
        }, "chefpay-online-orders-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<OrderDtos.OrderDto> orders) {
        statusLabel.setText(orders.size() + " online order(s)");
        orderRows.getChildren().clear();
        if (orders.isEmpty()) {
            orderRows.getChildren().add(new Label("No online orders right now - use \"+ Online Order\" on the "
                    + "Tables screen to log one that came in through an outside channel."));
            return;
        }
        for (OrderDtos.OrderDto order : orders) {
            orderRows.getChildren().add(buildRow(order));
        }
    }

    private HBox buildRow(OrderDtos.OrderDto order) {
        Label number = new Label(order.orderNumber());
        number.setStyle("-fx-font-weight: bold;");
        number.setPrefWidth(110);

        String customer = order.customerName() == null ? "-" : order.customerName()
                + (order.customerPhone() != null ? " (" + order.customerPhone() + ")" : "");
        Label customerLabel = new Label(customer);
        customerLabel.setPrefWidth(220);

        Label itemsLabel = new Label(order.items() == null ? "0 items" : order.items().size() + " item(s)");
        itemsLabel.setPrefWidth(90);

        Label statusLbl = new Label(order.status() == null ? "-" : order.status().replace('_', ' '));
        statusLbl.setPrefWidth(120);

        Label elapsedLbl = new Label(elapsedLabel(order.createdAt()));
        elapsedLbl.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        elapsedLbl.setPrefWidth(90);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button printKot = new Button("Print KOT");
        printKot.setOnAction(e -> {
            e.consume();
            int width = restaurantConfig == null ? 40 : restaurantConfig.receiptPaperWidthChars();
            ReceiptPrinter.show("KOT - " + order.orderNumber(), ReceiptPrinter.buildKotText(order, width));
        });

        Button open = new Button("Open");
        open.setOnAction(e -> {
            e.consume();
            onOrderOpened.accept(order);
        });

        HBox row = new HBox(12, number, customerLabel, itemsLabel, statusLbl, elapsedLbl, spacer);
        if (restaurantConfig != null && restaurantConfig.deliveryBoyFeatureEnabled()) {
            row.getChildren().add(buildDeliveryBoyControl(order));
        }
        row.getChildren().addAll(printKot, open);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-border-color: #eee; "
                + "-fx-border-radius: 8; -fx-border-width: 1;");
        return row;
    }

    /** The "assign a rider" control, only ever built when {@code
     * Restaurant.deliveryBoyFeatureEnabled} is on - see {@link #buildRow}. Options are every active
     * delivery boy plus "Unassigned"; picking one immediately calls {@code PATCH
     * /api/orders/{id}/delivery-boy} rather than waiting for a separate save action, matching how
     * every other single-field control on this row (Print KOT/Open) is a one-click action. */
    private ChoiceBox<String> buildDeliveryBoyControl(OrderDtos.OrderDto order) {
        ChoiceBox<String> choice = new ChoiceBox<>();
        choice.getItems().add(UNASSIGNED);
        deliveryBoys.forEach(d -> choice.getItems().add(d.name()));
        choice.setValue(order.deliveryBoyName() == null ? UNASSIGNED : order.deliveryBoyName());
        choice.setPrefWidth(140);
        choice.setOnAction(e -> {
            String selected = choice.getValue();
            if ((order.deliveryBoyName() == null && UNASSIGNED.equals(selected))
                    || selected != null && selected.equals(order.deliveryBoyName())) {
                return;
            }
            java.util.UUID deliveryBoyId = UNASSIGNED.equals(selected) ? null
                    : deliveryBoys.stream().filter(d -> d.name().equals(selected))
                            .map(DeliveryBoyDtos.DeliveryBoyDto::id).findFirst().orElse(null);
            assignDeliveryBoy(order, deliveryBoyId);
        });
        return choice;
    }

    private void assignDeliveryBoy(OrderDtos.OrderDto order, java.util.UUID deliveryBoyId) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/orders/" + order.id() + "/delivery-boy",
                        new OrderDtos.AssignDeliveryBoyRequest(deliveryBoyId, order.version()));
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING, "This order changed elsewhere - showing the latest version. Please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-online-order-assign-delivery-boy");
        worker.setDaemon(true);
        worker.start();
    }

    private String elapsedLabel(LocalDateTime createdAt) {
        if (createdAt == null) {
            return "";
        }
        long minutes = Duration.between(createdAt, LocalDateTime.now()).toMinutes();
        if (minutes < 0) {
            minutes = 0;
        }
        return minutes < 60 ? minutes + " min ago" : (minutes / 60) + "h " + (minutes % 60) + "m ago";
    }

    public Parent view() {
        return root;
    }
}
