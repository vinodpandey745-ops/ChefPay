package com.chefpay.javafx.billing;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.BillingDtos;
import com.chefpay.javafx.client.dto.ReportDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.common.ReceiptPrinter;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Operations &gt; Cash Flow / Expense / Withdrawal / Cash Top-Up / Day End (commercial-POS parity
 * round) - a client-only screen over billing endpoints that already existed
 * ({@code POST /api/billing/cash-movements}, {@code GET /api/billing/cash-summary}) plus the one
 * new list endpoint added alongside it ({@code GET /api/billing/cash-movements}). Deliberately
 * does NOT introduce a new {@code CashMovementType} - Expense and Withdrawal both post the
 * existing {@code CASH_OUT} type with a different pre-filled reason, and Cash Top-Up posts
 * {@code CASH_IN}; the ledger table's Reason column is what actually distinguishes them for
 * anyone reviewing the day, so no schema/enum risk was needed to get this UX. "Currency
 * Conversion" from the reference screenshots is out of scope - this app is single-currency
 * per restaurant ({@code Restaurant.currencySymbol}), and Day End here is a read-only report
 * (today's cash summary + sales summary side by side) rather than a persisted register-close
 * record, since this app has no {@code Shift}/register entity to close against yet.
 */
public class CashManagementView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final Label statusLabel = new Label();
    private final DatePicker datePicker = new DatePicker(LocalDate.now());

    private final TableView<BillingDtos.CashMovementDto> ledgerTable = new TableView<>();
    private final FlowPane summaryCards = new FlowPane(16, 16);
    private final FlowPane dayEndCashCards = new FlowPane(16, 16);
    private final FlowPane dayEndSalesCards = new FlowPane(16, 16);
    private final TableView<ReportDtos.PaymentMethodTotalDto> dayEndPaymentTable = new TableView<>();
    private final Button openDrawerButton = new Button("Open Drawer");

    private RestaurantDtos.RestaurantDto restaurantConfig;

    public CashManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;
        root.setTop(buildHeader());
        root.setCenter(buildBody());
    }

    private HBox buildHeader() {
        Label title = new Label("Cash Management");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        Label dateLabel = new Label("Date");
        datePicker.setPrefWidth(140);
        datePicker.setOnAction(e -> reload());

        Button today = new Button("Today");
        today.setOnAction(e -> { datePicker.setValue(LocalDate.now()); reload(); });

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        Button topUp = new Button("+ Cash Top-Up");
        topUp.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
        topUp.setOnAction(e -> movementDialog("CASH_IN", "Cash Top-Up", ""));

        Button expense = new Button("- Expense");
        expense.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold;");
        expense.setOnAction(e -> movementDialog("CASH_OUT", "Expense", "Expense"));

        Button withdrawal = new Button("- Withdrawal");
        withdrawal.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold;");
        withdrawal.setOnAction(e -> movementDialog("CASH_OUT", "Withdrawal", "Withdrawal"));

        // Only shown once Settings' Cash Drawer toggle is on AND a printer name is configured -
        // see ReceiptPrinter.openCashDrawer's javadoc for why both matter (this is a raw ESC/POS
        // pulse to a named OS print queue, not a guaranteed hardware action).
        openDrawerButton.setVisible(false);
        openDrawerButton.setManaged(false);
        openDrawerButton.setOnAction(e -> openCashDrawer());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(10, title, dateLabel, datePicker, today, refresh, topUp, expense, withdrawal,
                openDrawerButton, spacer, statusLabel);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private TabPane buildBody() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab ledgerTab = new Tab("Cash Ledger", buildLedgerTab());
        Tab dayEndTab = new Tab("Day End", buildDayEndTab());
        tabs.getTabs().addAll(ledgerTab, dayEndTab);
        return tabs;
    }

    private VBox buildLedgerTab() {
        summaryCards.setPadding(new Insets(0, 0, 8, 0));

        TableColumn<BillingDtos.CashMovementDto, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().type().replace('_', ' ')));
        typeCol.setPrefWidth(110);
        TableColumn<BillingDtos.CashMovementDto, BigDecimal> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().amount()));
        amountCol.setPrefWidth(110);
        TableColumn<BillingDtos.CashMovementDto, String> reasonCol = new TableColumn<>("Reason");
        reasonCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().reason()));
        reasonCol.setPrefWidth(260);
        TableColumn<BillingDtos.CashMovementDto, String> byCol = new TableColumn<>("Recorded By");
        byCol.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().recordedByName() == null ? "-" : data.getValue().recordedByName()));
        byCol.setPrefWidth(160);
        TableColumn<BillingDtos.CashMovementDto, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().recordedAt() == null ? "-"
                        : data.getValue().recordedAt().format(DateTimeFormatter.ofPattern("hh:mm a"))));
        timeCol.setPrefWidth(100);
        ledgerTable.getColumns().setAll(List.of(typeCol, amountCol, reasonCol, byCol, timeCol));
        ledgerTable.setPlaceholder(new Label("No cash movements recorded for this date."));
        VBox.setVgrow(ledgerTable, Priority.ALWAYS);

        VBox body = new VBox(16, summaryCards, ledgerTable);
        body.setPadding(new Insets(24));
        return body;
    }

    private ScrollPane buildDayEndTab() {
        Label cashTitle = sectionLabel("Cash Drawer Summary");
        Label salesTitle = sectionLabel("Sales Summary");

        TableColumn<ReportDtos.PaymentMethodTotalDto, String> methodCol = new TableColumn<>("Payment Method");
        methodCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().method()));
        methodCol.setPrefWidth(160);
        TableColumn<ReportDtos.PaymentMethodTotalDto, BigDecimal> methodTotalCol = new TableColumn<>("Total");
        methodTotalCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().total()));
        methodTotalCol.setPrefWidth(120);
        TableColumn<ReportDtos.PaymentMethodTotalDto, Long> methodCountCol = new TableColumn<>("Payments");
        methodCountCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().paymentCount()));
        methodCountCol.setPrefWidth(90);
        dayEndPaymentTable.getColumns().setAll(List.of(methodCol, methodTotalCol, methodCountCol));
        dayEndPaymentTable.setPrefHeight(200);
        dayEndPaymentTable.setPlaceholder(new Label("No payments on this date."));

        VBox body = new VBox(20, cashTitle, dayEndCashCards, salesTitle, dayEndSalesCards, dayEndPaymentTable);
        body.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        return label;
    }

    public void reload() {
        LocalDate date = datePicker.getValue() == null ? LocalDate.now() : datePicker.getValue();
        statusLabel.setText("Loading...");
        Thread worker = new Thread(() -> {
            try {
                var movementsData = apiClient.get("/api/billing/cash-movements?date=" + date);
                List<BillingDtos.CashMovementDto> movements = apiClient.convertList(movementsData, BillingDtos.CashMovementDto.class);

                var summaryData = apiClient.get("/api/billing/cash-summary?date=" + date);
                BillingDtos.CashSummaryDto summary = apiClient.convert(summaryData, BillingDtos.CashSummaryDto.class);

                var salesData = apiClient.get("/api/reports/sales?from=" + date + "&to=" + date);
                ReportDtos.SalesReportDto sales = apiClient.convert(salesData, ReportDtos.SalesReportDto.class);

                Platform.runLater(() -> {
                    statusLabel.setText("Updated just now");
                    renderLedger(movements, summary);
                    renderDayEnd(summary, sales);
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load cash data: " + ex.getMessage()));
            }
        }, "chefpay-cash-management-load");
        worker.setDaemon(true);
        worker.start();
        loadRestaurantConfig();
    }

    private void loadRestaurantConfig() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                Platform.runLater(() -> {
                    this.restaurantConfig = restaurant;
                    boolean showDrawer = restaurant.cashDrawerEnabled() && restaurant.receiptPrinterName() != null
                            && !restaurant.receiptPrinterName().isBlank();
                    openDrawerButton.setVisible(showDrawer);
                    openDrawerButton.setManaged(showDrawer);
                });
            } catch (ApiException ex) {
                // Non-fatal - the Open Drawer button just stays hidden until this loads.
            }
        }, "chefpay-cash-management-restaurant-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void openCashDrawer() {
        if (restaurantConfig == null || restaurantConfig.receiptPrinterName() == null) {
            return;
        }
        String printerName = restaurantConfig.receiptPrinterName();
        Thread worker = new Thread(() -> {
            boolean ok = ReceiptPrinter.openCashDrawer(printerName);
            Platform.runLater(() -> {
                if (!ok) {
                    new Alert(Alert.AlertType.WARNING, "Could not reach printer \"" + printerName + "\" to open the "
                            + "drawer - check it's on, connected, and the name in Settings matches the OS printer "
                            + "queue name exactly.").showAndWait();
                }
            });
        }, "chefpay-cash-drawer-open");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderLedger(List<BillingDtos.CashMovementDto> movements, BillingDtos.CashSummaryDto summary) {
        ledgerTable.getItems().setAll(movements);
        summaryCards.getChildren().setAll(
                card("Cash Payments", "₹" + summary.totalCashPayments(), "#27ae60"),
                card("Cash In", "₹" + summary.totalCashIn(), "#2980b9"),
                card("Cash Out", "₹" + summary.totalCashOut(), "#c0392b"),
                card("Expected in Drawer", "₹" + summary.expectedCashInDrawer(), "#2c3e50"));
    }

    private void renderDayEnd(BillingDtos.CashSummaryDto summary, ReportDtos.SalesReportDto sales) {
        dayEndCashCards.getChildren().setAll(
                card("Cash Payments", "₹" + summary.totalCashPayments(), "#27ae60"),
                card("Cash In", "₹" + summary.totalCashIn(), "#2980b9"),
                card("Cash Out", "₹" + summary.totalCashOut(), "#c0392b"),
                card("Expected in Drawer", "₹" + summary.expectedCashInDrawer(), "#2c3e50"));
        dayEndSalesCards.getChildren().setAll(
                card("Total Sales", "₹" + sales.totalSales(), "#27ae60"),
                card("Orders", String.valueOf(sales.orderCount()), "#2c3e50"),
                card("Payments", String.valueOf(sales.paymentCount()), "#2c3e50"),
                card("Total Tips", "₹" + sales.totalTips(), "#8e44ad"));
        dayEndPaymentTable.getItems().setAll(sales.paymentMethodBreakdown() == null ? List.of() : sales.paymentMethodBreakdown());
    }

    private VBox card(String label, String value, String accentColor) {
        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: " + accentColor + ";");
        Label labelLabel = new Label(label);
        labelLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        VBox box = new VBox(6, valueLabel, labelLabel);
        box.setPadding(new Insets(14, 20, 14, 20));
        box.setPrefWidth(170);
        box.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e0e0e0; "
                + "-fx-border-radius: 10; -fx-border-width: 1; -fx-border-color: " + accentColor + "22;");
        return box;
    }

    private void movementDialog(String type, String dialogTitle, String prefilledReason) {
        Dialog<BillingDtos.CreateCashMovementRequest> dialog = new Dialog<>();
        dialog.setTitle(dialogTitle);
        ButtonType saveType = new ButtonType("Record", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField amount = new TextField();
        amount.setPromptText("0.00");
        TextField reason = new TextField(prefilledReason);
        reason.setPromptText("Reason");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Amount"), amount);
        grid.addRow(1, new Label("Reason"), reason);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            if (reason.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter a reason.").showAndWait();
                return null;
            }
            try {
                BigDecimal amt = new BigDecimal(amount.getText().trim());
                if (amt.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new NumberFormatException();
                }
                return new BillingDtos.CreateCashMovementRequest(type, amt, reason.getText().trim());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid positive amount.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/billing/cash-movements", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-cash-movement-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    public Parent view() {
        return root;
    }
}
