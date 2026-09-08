package com.chefpay.javafx.reports;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.ReportDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 5's Reports slice (ARCHITECTURE.md §15), expanded in the "full-fledged POS" round to add a
 * report-type selector over the same single {@code GET /api/reports/sales} round trip (one date
 * range fetch already carries every breakdown the server computes - see {@code ReportService}) -
 * Category Summary, Order Type Summary, Employee Summary (sales collected, attributed to
 * {@code Payment.receivedBy}) and Tip Summary (attributed to {@code Order.waiter} instead - see
 * {@code ReportDtos.TipTotalDto}'s javadoc for why those two are deliberately different
 * attributions). The always-visible Overview (payment methods + top items) stays the default.
 * Executive/Group/Variation/Cover-Size/Counter/Locality/Captain-wise summaries seen on commercial
 * POS competitors are real follow-ups once there's demand for that granularity - this app doesn't
 * yet track table-cover-count, delivery locality, or a distinct "captain" role, so faking those
 * reports would just show empty tables.
 */
public class ReportsView {

    private static final String REPORT_OVERVIEW = "Overview (Payments + Top Items)";
    private static final String REPORT_CATEGORY = "Category Summary";
    private static final String REPORT_ORDER_TYPE = "Order Type Summary";
    private static final String REPORT_EMPLOYEE = "Employee Summary (Sales Collected)";
    private static final String REPORT_TIP = "Tip Summary";

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final Label statusLabel = new Label();
    private final DatePicker fromPicker = new DatePicker(LocalDate.now());
    private final DatePicker toPicker = new DatePicker(LocalDate.now());
    private final ChoiceBox<String> reportTypeChoice = new ChoiceBox<>();
    private final FlowPane cards = new FlowPane(16, 16);
    private final TableView<ReportDtos.PaymentMethodTotalDto> paymentTable = new TableView<>();
    private final TableView<ReportDtos.TopItemDto> itemsTable = new TableView<>();
    private final TableView<ReportDtos.CategoryTotalDto> categoryTable = new TableView<>();
    private final TableView<ReportDtos.OrderTypeTotalDto> orderTypeTable = new TableView<>();
    private final TableView<ReportDtos.EmployeeTotalDto> employeeTable = new TableView<>();
    private final TableView<ReportDtos.TipTotalDto> tipTable = new TableView<>();
    private final VBox overviewSection = new VBox(16);
    private final VBox categorySection = new VBox(16);
    private final VBox orderTypeSection = new VBox(16);
    private final VBox employeeSection = new VBox(16);
    private final VBox tipSection = new VBox(16);
    private final VBox detailArea = new VBox();

    private ReportDtos.SalesReportDto lastReport;

    public ReportsView(ApiClient apiClient) {
        this.apiClient = apiClient;
        root.setTop(buildHeader());
        root.setCenter(buildBody());
    }

    private HBox buildHeader() {
        Label title = new Label("Reports");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        Label fromLabel = new Label("From");
        Label toLabel = new Label("To");
        fromPicker.setPrefWidth(140);
        toPicker.setPrefWidth(140);

        Button today = new Button("Today");
        today.setOnAction(e -> { fromPicker.setValue(LocalDate.now()); toPicker.setValue(LocalDate.now()); reload(); });
        Button last7 = new Button("Last 7 Days");
        last7.setOnAction(e -> { fromPicker.setValue(LocalDate.now().minusDays(6)); toPicker.setValue(LocalDate.now()); reload(); });
        Button thisMonth = new Button("This Month");
        thisMonth.setOnAction(e -> { fromPicker.setValue(LocalDate.now().withDayOfMonth(1)); toPicker.setValue(LocalDate.now()); reload(); });

        Button run = new Button("Run Report");
        run.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        run.setOnAction(e -> reload());

        reportTypeChoice.getItems().setAll(REPORT_OVERVIEW, REPORT_CATEGORY, REPORT_ORDER_TYPE, REPORT_EMPLOYEE, REPORT_TIP);
        reportTypeChoice.setValue(REPORT_OVERVIEW);
        reportTypeChoice.setOnAction(e -> showSelectedSection());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox controls = new HBox(8, fromLabel, fromPicker, toLabel, toPicker, today, last7, thisMonth, run,
                new Label("Report:"), reportTypeChoice);
        controls.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(16, title, controls, spacer, statusLabel);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private VBox buildBody() {
        cards.setPadding(new Insets(0, 0, 8, 0));

        // NOTE: cellValueFactory is wired with explicit lambdas rather than PropertyValueFactory -
        // these DTOs are records (accessor methods like method(), not JavaBean-style getMethod()),
        // and PropertyValueFactory's reflection only ever looks for the JavaBean naming convention,
        // so it would silently render every cell blank instead of failing loudly.
        TableColumn<ReportDtos.PaymentMethodTotalDto, String> methodCol = new TableColumn<>("Payment Method");
        methodCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().method()));
        methodCol.setPrefWidth(160);
        TableColumn<ReportDtos.PaymentMethodTotalDto, java.math.BigDecimal> methodTotalCol = new TableColumn<>("Total");
        methodTotalCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().total()));
        methodTotalCol.setPrefWidth(120);
        TableColumn<ReportDtos.PaymentMethodTotalDto, Long> methodCountCol = new TableColumn<>("Payments");
        methodCountCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().paymentCount()));
        methodCountCol.setPrefWidth(90);
        paymentTable.getColumns().setAll(java.util.List.of(methodCol, methodTotalCol, methodCountCol));
        paymentTable.setPrefHeight(220);
        paymentTable.setPlaceholder(new Label("No payments in this range."));

        TableColumn<ReportDtos.TopItemDto, String> itemNameCol = new TableColumn<>("Item");
        itemNameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().menuItemName()));
        itemNameCol.setPrefWidth(220);
        TableColumn<ReportDtos.TopItemDto, java.math.BigDecimal> itemQtyCol = new TableColumn<>("Qty Sold");
        itemQtyCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().quantitySold()));
        itemQtyCol.setPrefWidth(100);
        TableColumn<ReportDtos.TopItemDto, java.math.BigDecimal> itemRevenueCol = new TableColumn<>("Revenue");
        itemRevenueCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().revenue()));
        itemRevenueCol.setPrefWidth(120);
        itemsTable.getColumns().setAll(java.util.List.of(itemNameCol, itemQtyCol, itemRevenueCol));
        itemsTable.setPrefHeight(320);
        itemsTable.setPlaceholder(new Label("No items sold in this range."));

        overviewSection.getChildren().addAll(
                sectionLabel("Payment Method Breakdown"), paymentTable,
                sectionLabel("Top-Selling Items"), itemsTable);
        VBox.setVgrow(itemsTable, Priority.ALWAYS);

        TableColumn<ReportDtos.CategoryTotalDto, String> catNameCol = new TableColumn<>("Category");
        catNameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().categoryName()));
        catNameCol.setPrefWidth(220);
        TableColumn<ReportDtos.CategoryTotalDto, java.math.BigDecimal> catQtyCol = new TableColumn<>("Qty Sold");
        catQtyCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().quantitySold()));
        catQtyCol.setPrefWidth(100);
        TableColumn<ReportDtos.CategoryTotalDto, java.math.BigDecimal> catRevenueCol = new TableColumn<>("Revenue");
        catRevenueCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().revenue()));
        catRevenueCol.setPrefWidth(120);
        categoryTable.getColumns().setAll(java.util.List.of(catNameCol, catQtyCol, catRevenueCol));
        categoryTable.setPrefHeight(400);
        categoryTable.setPlaceholder(new Label("No sales in this range."));
        categorySection.getChildren().addAll(sectionLabel("Category Summary"), categoryTable);
        VBox.setVgrow(categoryTable, Priority.ALWAYS);

        TableColumn<ReportDtos.OrderTypeTotalDto, String> typeNameCol = new TableColumn<>("Order Type");
        typeNameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().orderType().replace('_', ' ')));
        typeNameCol.setPrefWidth(200);
        TableColumn<ReportDtos.OrderTypeTotalDto, Long> typeCountCol = new TableColumn<>("Orders");
        typeCountCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().orderCount()));
        typeCountCol.setPrefWidth(100);
        TableColumn<ReportDtos.OrderTypeTotalDto, java.math.BigDecimal> typeRevenueCol = new TableColumn<>("Revenue");
        typeRevenueCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().revenue()));
        typeRevenueCol.setPrefWidth(120);
        orderTypeTable.getColumns().setAll(java.util.List.of(typeNameCol, typeCountCol, typeRevenueCol));
        orderTypeTable.setPrefHeight(400);
        orderTypeTable.setPlaceholder(new Label("No orders in this range."));
        orderTypeSection.getChildren().addAll(sectionLabel("Order Type Summary"), orderTypeTable);
        VBox.setVgrow(orderTypeTable, Priority.ALWAYS);

        TableColumn<ReportDtos.EmployeeTotalDto, String> empNameCol = new TableColumn<>("Employee");
        empNameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().employeeName()));
        empNameCol.setPrefWidth(220);
        TableColumn<ReportDtos.EmployeeTotalDto, java.math.BigDecimal> empTotalCol = new TableColumn<>("Total Collected");
        empTotalCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().totalCollected()));
        empTotalCol.setPrefWidth(140);
        TableColumn<ReportDtos.EmployeeTotalDto, Long> empCountCol = new TableColumn<>("Payments");
        empCountCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().paymentCount()));
        empCountCol.setPrefWidth(100);
        employeeTable.getColumns().setAll(java.util.List.of(empNameCol, empTotalCol, empCountCol));
        employeeTable.setPrefHeight(400);
        employeeTable.setPlaceholder(new Label("No payments recorded in this range."));
        Label empHelp = new Label("Attributed to whoever processed each payment - see help text in docs for why this "
                + "can differ from Tip Summary on split-bill orders.");
        empHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        employeeSection.getChildren().addAll(sectionLabel("Employee Summary - Sales Collected"), empHelp, employeeTable);
        VBox.setVgrow(employeeTable, Priority.ALWAYS);

        TableColumn<ReportDtos.TipTotalDto, String> tipNameCol = new TableColumn<>("Waiter");
        tipNameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().waiterName()));
        tipNameCol.setPrefWidth(220);
        TableColumn<ReportDtos.TipTotalDto, java.math.BigDecimal> tipTotalCol = new TableColumn<>("Total Tips");
        tipTotalCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().totalTips()));
        tipTotalCol.setPrefWidth(140);
        TableColumn<ReportDtos.TipTotalDto, Long> tipCountCol = new TableColumn<>("Orders");
        tipCountCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().orderCount()));
        tipCountCol.setPrefWidth(100);
        tipTable.getColumns().setAll(java.util.List.of(tipNameCol, tipTotalCol, tipCountCol));
        tipTable.setPrefHeight(400);
        tipTable.setPlaceholder(new Label("No tips recorded in this range."));
        Label tipHelp = new Label("Attributed to Order.waiter - who served the table, not who collected the payment.");
        tipHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        tipSection.getChildren().addAll(sectionLabel("Tip Summary"), tipHelp, tipTable);
        VBox.setVgrow(tipTable, Priority.ALWAYS);

        detailArea.getChildren().setAll(overviewSection);
        VBox.setVgrow(detailArea, Priority.ALWAYS);

        VBox body = new VBox(16, cards, detailArea);
        body.setPadding(new Insets(24));
        VBox.setVgrow(detailArea, Priority.ALWAYS);

        return body;
    }

    private void showSelectedSection() {
        String selected = reportTypeChoice.getValue();
        VBox section = switch (selected == null ? REPORT_OVERVIEW : selected) {
            case REPORT_CATEGORY -> categorySection;
            case REPORT_ORDER_TYPE -> orderTypeSection;
            case REPORT_EMPLOYEE -> employeeSection;
            case REPORT_TIP -> tipSection;
            default -> overviewSection;
        };
        detailArea.getChildren().setAll(section);
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        return label;
    }

    public void reload() {
        LocalDate from = fromPicker.getValue() == null ? LocalDate.now() : fromPicker.getValue();
        LocalDate to = toPicker.getValue() == null ? LocalDate.now() : toPicker.getValue();
        if (to.isBefore(from)) {
            statusLabel.setText("'To' date can't be before 'From' date.");
            return;
        }
        statusLabel.setText("Loading...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/reports/sales?from=" + from + "&to=" + to);
                ReportDtos.SalesReportDto report = apiClient.convert(data, ReportDtos.SalesReportDto.class);
                Platform.runLater(() -> renderReport(report));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load report: " + ex.getMessage()));
            }
        }, "chefpay-reports-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderReport(ReportDtos.SalesReportDto report) {
        this.lastReport = report;
        statusLabel.setText("Updated just now");
        cards.getChildren().setAll(
                card("Total Sales", "₹" + report.totalSales(), "#27ae60"),
                card("Orders", String.valueOf(report.orderCount()), "#2c3e50"),
                card("Payments", String.valueOf(report.paymentCount()), "#2c3e50"),
                card("Avg Order Value", "₹" + report.averageOrderValue(), "#2980b9"),
                card("Total Tips", "₹" + report.totalTips(), "#8e44ad")
        );
        paymentTable.getItems().setAll(report.paymentMethodBreakdown());
        itemsTable.getItems().setAll(report.topItems());
        categoryTable.getItems().setAll(report.categoryBreakdown() == null ? List.of() : report.categoryBreakdown());
        orderTypeTable.getItems().setAll(report.orderTypeBreakdown() == null ? List.of() : report.orderTypeBreakdown());
        employeeTable.getItems().setAll(report.employeeBreakdown() == null ? List.of() : report.employeeBreakdown());
        tipTable.getItems().setAll(report.tipBreakdown() == null ? List.of() : report.tipBreakdown());
        showSelectedSection();
    }

    private VBox card(String label, String value, String accentColor) {
        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + accentColor + ";");
        Label labelLabel = new Label(label);
        labelLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        VBox box = new VBox(6, valueLabel, labelLabel);
        box.setPadding(new Insets(16, 24, 16, 24));
        box.setPrefWidth(180);
        box.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e0e0e0; "
                + "-fx-border-radius: 10; -fx-border-width: 1; -fx-border-color: " + accentColor + "22;");
        return box;
    }

    public Parent view() {
        return root;
    }
}
