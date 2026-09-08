package com.chefpay.javafx.kitchen;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.StompWebSocketClient;
import com.chefpay.javafx.client.dto.OrderDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
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
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Kitchen Display System (Phase 3, requirement §16-§20/§39). One ticket per order, oldest first
 * (server-side FIFO from {@code KitchenService.listQueue}); each line shows a big touch-friendly
 * "advance to next status" button rather than a dropdown, per requirement §39's KDS mockup. Ticket
 * age is color-coded (requirement's "order delay monitoring") using each line's {@code sentAt} -
 * this is purely a *display* cue; the server's state machine is still the only thing that decides
 * whether a transition is actually allowed (§61 - no business rules re-implemented here).
 *
 * <p>Like {@link com.chefpay.javafx.orders.OrderTakingView}, this never trusts a WebSocket payload
 * as authoritative - any {@code /topic/orders} event just triggers a full queue refetch, plus a
 * defensive 15s poll in case an event is ever missed on an always-on, usually-unattended screen.
 */
public class KitchenDisplayView {

    /** Kitchen-facing forward path a KDS button offers next; the server's state machine has final say. */
    private static final Map<String, String> NEXT_STATUS = Map.of(
            "SENT", "ACCEPTED",
            "ACCEPTED", "PREPARING",
            "PREPARING", "READY",
            "READY", "SERVED"
    );

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final StompWebSocketClient wsClient;
    private final FlowPane ticketBoard = new FlowPane(20, 20);
    private final Label statusLabel = new Label();
    private Timeline pollTimeline;
    /** Round 12 §6: "DETAILED" (today's per-item flow) or "SIMPLE" (one Serve All action per
     * ticket) - see {@code KitchenService#serveAllItems}'s javadoc. Refreshed on every {@link
     * #start()} so a Settings change takes effect the next time this screen is opened. */
    private String kitchenServiceMode = "DETAILED";

    public KitchenDisplayView(ApiClient apiClient, StompWebSocketClient wsClient) {
        this.apiClient = apiClient;
        this.wsClient = wsClient;

        root.setTop(buildHeader());
        root.setCenter(buildBoard());

        wsClient.subscribe("/topic/orders", (dest, body) -> reload());
    }

    private void loadRestaurantConfig() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                kitchenServiceMode = restaurant.kitchenServiceMode();
                // Board may already be showing tickets built under the previous mode (or this is
                // the very first paint racing this async load) - re-render now that we know which
                // mode is actually configured, rather than waiting for the next poll/WS event.
                Platform.runLater(this::reload);
            } catch (ApiException ignored) {
                // keep whatever mode was already in effect rather than blocking the screen
            }
        }, "chefpay-kitchen-config-load");
        worker.setDaemon(true);
        worker.start();
    }

    private HBox buildHeader() {
        Label title = new Label("Kitchen Display");
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

    private ScrollPane buildBoard() {
        ticketBoard.setPadding(new Insets(16));
        ScrollPane scroll = new ScrollPane(ticketBoard);
        scroll.setFitToWidth(true);
        return scroll;
    }

    /** Call when this screen becomes visible - starts the defensive poll and does an immediate load. */
    public void start() {
        loadRestaurantConfig();
        reload();
        if (pollTimeline == null) {
            pollTimeline = new Timeline(new KeyFrame(Duration.seconds(15), e -> reload()));
            pollTimeline.setCycleCount(Animation.INDEFINITE);
        }
        pollTimeline.play();
    }

    /** Call when navigating away - no point polling a screen nobody's looking at. */
    public void stop() {
        if (pollTimeline != null) {
            pollTimeline.stop();
        }
    }

    private void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/kitchen/queue");
                List<OrderDtos.OrderDto> tickets = apiClient.convertList(data, OrderDtos.OrderDto.class);
                Platform.runLater(() -> renderBoard(tickets));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load queue: " + ex.getMessage()));
            }
        }, "chefpay-kitchen-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderBoard(List<OrderDtos.OrderDto> tickets) {
        statusLabel.setText(tickets.size() + " active ticket" + (tickets.size() == 1 ? "" : "s"));
        ticketBoard.getChildren().clear();
        for (OrderDtos.OrderDto order : tickets) {
            ticketBoard.getChildren().add(buildTicket(order));
        }
    }

    private VBox buildTicket(OrderDtos.OrderDto order) {
        Label header = new Label(order.orderNumber() + (order.tableName() != null ? "  •  " + order.tableName() : ""));
        header.setStyle("-fx-font-size: 19px; -fx-font-weight: bold;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox headerRow;
        VBox lines;
        // Round 12 §6/§7 - "SIMPLE" mode collapses the whole ticket to a single "Serve All" action
        // (server-gated by KitchenService#serveAllItems - this button is only ever a convenience,
        // never the actual authorization). "DETAILED" keeps today's per-item + Advance All flow
        // completely unchanged so nothing regresses for installs that don't opt in.
        if ("SIMPLE".equals(kitchenServiceMode)) {
            Button serveAll = new Button("Serve All ▸");
            serveAll.setStyle("-fx-background-color: #16a085; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;"
                    + " -fx-padding: 10 18 10 18;");
            serveAll.setOnAction(e -> serveAll(order));
            headerRow = new HBox(8, header, spacer, serveAll);
            headerRow.setAlignment(Pos.CENTER_LEFT);

            lines = new VBox(8);
            for (OrderDtos.OrderItemDto item : order.items()) {
                Label name = new Label(item.quantity() + " x " + item.menuItemName()
                        + (item.specialInstructions() == null || item.specialInstructions().isBlank() ? "" : " (" + item.specialInstructions() + ")"));
                name.setWrapText(true);
                name.setMaxWidth(260);
                name.setStyle("-fx-font-size: 14px;");
                Label status = new Label(item.status().replace('_', ' '));
                status.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
                lines.getChildren().add(new VBox(2, name, status));
            }
        } else {
            // "Receive All"/"Advance All" - one click to move every still-in-flight item on this
            // ticket forward a step, instead of tapping each item's own advance button one at a time.
            Button advanceAll = new Button("Advance All ▸");
            advanceAll.setStyle("-fx-background-color: #16a085; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;"
                    + " -fx-padding: 8 14 8 14;");
            advanceAll.setOnAction(e -> advanceAll(order));
            headerRow = new HBox(8, header, spacer, advanceAll);
            headerRow.setAlignment(Pos.CENTER_LEFT);

            lines = new VBox(8);
            for (OrderDtos.OrderItemDto item : order.items()) {
                lines.getChildren().add(buildItemRow(order, item));
            }
        }

        VBox ticket = new VBox(12, headerRow, lines);
        ticket.setPadding(new Insets(18));
        // Round 12 §7 - larger ticket footprint for cross-kitchen visibility. FlowPane still wraps
        // responsively (requirement's "without becoming oversized") - this just raises the floor.
        ticket.setPrefWidth(380);
        ticket.setMinWidth(360);
        ticket.setStyle("-fx-background-color: white; -fx-border-color: " + ticketBorderColor(order)
                + "; -fx-border-width: 2; -fx-border-radius: 8; -fx-background-radius: 8;");
        return ticket;
    }

    /** Fires {@code POST /api/kitchen/orders/{id}/serve-all} - see {@code KitchenService
     * #serveAllItems}'s javadoc. Same "ignore the response, do a full reload" discipline as {@link
     * #advanceAll}. Server-side still enforces {@code kitchenServiceMode == SIMPLE} independently
     * of whether this button is shown, so a stale client can never bypass the detailed workflow. */
    private void serveAll(OrderDtos.OrderDto order) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.post("/api/kitchen/orders/" + order.id() + "/serve-all?version=" + order.version(), null);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Could not serve ticket: " + ex.getMessage());
                    reload(); // most likely a version conflict from a concurrent change - resync
                });
            }
        }, "chefpay-kitchen-serve-all");
        worker.setDaemon(true);
        worker.start();
    }

    /** Fires {@code POST /api/kitchen/orders/{id}/advance-all} - see {@code KitchenService
     * #advanceAllItems}'s javadoc for exactly which items move and by how much. Same "ignore the
     * response, do a full reload" discipline as {@link #advanceItem} - the queue spans many
     * orders, a full reload keeps this screen consistent with the server in one place. */
    private void advanceAll(OrderDtos.OrderDto order) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.post("/api/kitchen/orders/" + order.id() + "/advance-all?version=" + order.version(), null);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Could not advance ticket: " + ex.getMessage());
                    reload(); // most likely a version conflict from a concurrent change - resync
                });
            }
        }, "chefpay-kitchen-advance-all");
        worker.setDaemon(true);
        worker.start();
    }

    /** Oldest still-active line on the ticket decides its urgency color - a coarse but honest delay signal. */
    private String ticketBorderColor(OrderDtos.OrderDto order) {
        long oldestMinutes = order.items().stream()
                .map(OrderDtos.OrderItemDto::sentAt)
                .filter(java.util.Objects::nonNull)
                .mapToLong(sentAt -> ChronoUnit.MINUTES.between(sentAt, LocalDateTime.now()))
                .max().orElse(0);
        if (oldestMinutes >= 15) {
            return "#e74c3c"; // red - seriously delayed, needs attention now
        }
        if (oldestMinutes >= 8) {
            return "#f39c12"; // amber - getting slow
        }
        return "#2ecc71"; // green - on track
    }

    private HBox buildItemRow(OrderDtos.OrderDto order, OrderDtos.OrderItemDto item) {
        Label name = new Label(item.quantity() + " x " + item.menuItemName()
                + (item.specialInstructions() == null || item.specialInstructions().isBlank() ? "" : " (" + item.specialInstructions() + ")"));
        name.setWrapText(true);
        name.setMaxWidth(230);
        name.setStyle("-fx-font-size: 14px;");

        Label status = new Label(item.status().replace('_', ' '));
        status.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        VBox nameBox = new VBox(2, name, status);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        String next = NEXT_STATUS.get(item.status());
        HBox row = new HBox(10, nameBox, spacer);
        row.setAlignment(Pos.CENTER_LEFT);

        if (next != null) {
            Button advance = new Button(actionLabel(next));
            advance.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;"
                    + " -fx-font-size: 13px; -fx-padding: 8 14 8 14;");
            advance.setOnAction(e -> advanceItem(order, item, next));
            row.getChildren().add(advance);
        }
        return row;
    }

    private String actionLabel(String nextStatus) {
        return switch (nextStatus) {
            case "ACCEPTED" -> "Accept";
            case "PREPARING" -> "Start";
            case "READY" -> "Ready";
            case "SERVED" -> "Served";
            default -> nextStatus;
        };
    }

    private void advanceItem(OrderDtos.OrderDto order, OrderDtos.OrderItemDto item, String nextStatus) {
        var request = new OrderDtos.UpdateItemStatusRequest(nextStatus, null, order.version());
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/orders/" + order.id() + "/items/" + item.id() + "/status", request);
                // Don't try to patch local state from the response - the queue spans many orders
                // and a full reload keeps this screen's cross-order view consistent with the
                // server in one place, same "never trust a stale local copy" rule as elsewhere.
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Could not update: " + ex.getMessage());
                    reload(); // most likely a version conflict from a concurrent change - resync
                });
            }
        }, "chefpay-kitchen-advance");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
