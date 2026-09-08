package com.chefpay.javafx.kot;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.KotDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Read-only KOT (Kitchen Order Ticket) listing - Round 8's KOT Listing screen. Simply repaints
 * straight from {@code GET /api/kot/tickets}, which already returns tickets newest-first, so this
 * class does no re-sorting of its own. No mutations happen here at all, so there is nothing to
 * gate on a permission client-side.
 */
public class KotListingView {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a");

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox ticketsBox = new VBox(16);
    private final Label statusLabel = new Label();

    public KotListingView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(ticketsBox);
        scroll.setFitToWidth(true);
        ticketsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("KOT Listing");
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
                var data = apiClient.get("/api/kot/tickets");
                List<KotDtos.KotTicketDto> tickets = apiClient.convertList(data, KotDtos.KotTicketDto.class);
                Platform.runLater(() -> render(tickets));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load KOT tickets: " + ex.getMessage()));
            }
        }, "chefpay-kot-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<KotDtos.KotTicketDto> tickets) {
        statusLabel.setText(tickets.size() + " ticket(s)");
        ticketsBox.getChildren().clear();
        if (tickets.isEmpty()) {
            ticketsBox.getChildren().add(new Label("No KOT tickets yet."));
            return;
        }
        for (KotDtos.KotTicketDto ticket : tickets) {
            ticketsBox.getChildren().add(buildTicketCard(ticket));
        }
    }

    private VBox buildTicketCard(KotDtos.KotTicketDto ticket) {
        Label header = new Label("KOT #" + ticket.kotNumber() + "   " + ticket.orderNumber()
                + "   " + (ticket.tableName() == null ? "-" : ticket.tableName())
                + "   " + (ticket.orderType() == null ? "-" : ticket.orderType().replace('_', ' '))
                + "   " + (ticket.sentAt() == null ? "-" : ticket.sentAt().format(TIMESTAMP_FORMAT)));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        VBox card = new VBox(6, header, new Separator());
        card.setPadding(new Insets(12));
        card.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-background-color: #fafafa;");

        List<KotDtos.KotTicketItemDto> items = ticket.items() == null ? List.of() : ticket.items();
        boolean anyShown = false;
        for (KotDtos.KotTicketItemDto item : items) {
            if ("CANCELLED".equals(item.status()) || "VOIDED".equals(item.status())) {
                continue;
            }
            anyShown = true;
            Label line = new Label("    " + item.quantity() + " x " + item.menuItemName());
            line.setStyle("-fx-font-size: 13px;");
            card.getChildren().add(line);
            if (item.specialInstructions() != null && !item.specialInstructions().isBlank()) {
                Label note = new Label("        " + item.specialInstructions());
                note.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
                card.getChildren().add(note);
            }
        }
        if (!anyShown) {
            Label none = new Label("    (no active items)");
            none.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
            card.getChildren().add(none);
        }
        return card;
    }

    public Parent view() {
        return root;
    }
}
