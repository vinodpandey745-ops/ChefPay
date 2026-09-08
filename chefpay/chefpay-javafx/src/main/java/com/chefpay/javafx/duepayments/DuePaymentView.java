package com.chefpay.javafx.duepayments;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.DuePaymentDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Due Payment Management - Round 8's monitoring list of unpaid/partially-paid billed orders. Purely
 * read-only: there is no write endpoint here, and recording an actual payment against an order
 * still happens in {@code BillingView}, which this screen does not touch. Same
 * "re-render straight from the server's response" discipline as every other screen here.
 */
public class DuePaymentView {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a");

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox rowsBox = new VBox(0);
    private final Label statusLabel = new Label();

    public DuePaymentView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        rowsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Due Payments");
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
                var data = apiClient.get("/api/due-payments");
                List<DuePaymentDtos.DuePaymentDto> dues = apiClient.convertList(data, DuePaymentDtos.DuePaymentDto.class);
                Platform.runLater(() -> render(dues));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load due payments: " + ex.getMessage()));
            }
        }, "chefpay-due-payments-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<DuePaymentDtos.DuePaymentDto> dues) {
        BigDecimal total = dues.stream().map(DuePaymentDtos.DuePaymentDto::totalAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        statusLabel.setText(dues.size() + " order(s)  •  ₹" + total);

        rowsBox.getChildren().clear();
        if (dues.isEmpty()) {
            rowsBox.getChildren().add(new Label("No due payments - everything billed is fully paid."));
            return;
        }
        Label columnHeader = new Label(String.format("%-14s %-14s %-24s %-14s %-18s %-20s",
                "Order #", "Table", "Customer", "Amount Due", "Status", "Billed At"));
        columnHeader.setStyle("-fx-font-family: monospace; -fx-font-weight: bold; -fx-text-fill: #888; -fx-font-size: 11px;");
        rowsBox.getChildren().add(columnHeader);
        for (DuePaymentDtos.DuePaymentDto due : dues) {
            rowsBox.getChildren().add(buildRow(due));
        }
    }

    private HBox buildRow(DuePaymentDtos.DuePaymentDto due) {
        Label orderNumber = new Label(due.orderNumber());
        orderNumber.setPrefWidth(120);

        Label table = new Label(due.tableName() == null ? "-" : due.tableName());
        table.setPrefWidth(120);

        String customer = due.customerName() == null ? "-" : due.customerName();
        if (due.customerPhone() != null) {
            customer = customer + " (" + due.customerPhone() + ")";
        }
        Label customerLabel = new Label(customer);
        customerLabel.setPrefWidth(220);

        Label amount = new Label("₹" + due.totalAmount());
        amount.setPrefWidth(120);

        Label status = new Label(due.paymentStatus() == null ? "-" : due.paymentStatus().replace('_', ' '));
        status.setPrefWidth(150);

        Label billedAt = new Label(due.billedAt() == null ? "-" : due.billedAt().format(TIMESTAMP_FORMAT));
        billedAt.setPrefWidth(170);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, orderNumber, table, customerLabel, amount, status, billedAt, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-border-color: #eee; -fx-border-width: 0 0 1 0;");
        return row;
    }

    public Parent view() {
        return root;
    }
}
