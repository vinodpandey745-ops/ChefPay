package com.chefpay.javafx.dashboard;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.DashboardDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

/**
 * "What does the floor look like right now" - Phase 5's Dashboard slice (ARCHITECTURE.md §13),
 * extended by Round 12 §5 with a configurable "Graphical" chart view alongside the original
 * "Standard" KPI cards. Pulls from {@code GET /api/dashboard/summary} (cards) and, when the
 * restaurant's {@code dashboardViewMode} calls for it, {@code GET /api/dashboard/analytics}
 * (charts) - never assembling numbers client-side from several endpoints, keeping this screen a
 * thin renderer like every other view in this app.
 */
public class DashboardView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final FlowPane cards = new FlowPane(16, 16);
    /** Round 12 §5 - the chart section, built fresh on every reload alongside (or instead of, per
     * {@link #dashboardViewMode}) the KPI cards above it. */
    private final VBox chartsSection = new VBox(20);
    private final VBox contentBox = new VBox(20, cards, chartsSection);
    private final Label statusLabel = new Label();
    /** Configurable restaurant logo (Settings > Restaurant Logo) - well-aligned at the top of this
     * screen alongside the title, per its own request. Stays blank/hidden until (and unless) a
     * logo is actually configured; no visual placeholder box for the common "no logo set" case. */
    private final ImageView logoView = new ImageView();
    /** "STANDARD" (today's KPI cards only, unchanged default), "GRAPHICAL" (charts only), or
     * "BOTH" - refreshed on every {@link #reload}, same "Settings change takes effect next visit"
     * convention {@code KitchenDisplayView#kitchenServiceMode} already uses. */
    private String dashboardViewMode = "STANDARD";

    public DashboardView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        contentBox.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(contentBox);
        scroll.setFitToWidth(true);
        root.setCenter(scroll);
        loadRestaurantLogo();
    }

    private HBox buildHeader() {
        logoView.setFitHeight(40);
        logoView.setFitWidth(40);
        logoView.setPreserveRatio(true);
        logoView.setSmooth(true);
        logoView.setManaged(false);
        logoView.setVisible(false);

        Label title = new Label("Dashboard");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, logoView, title, spacer, statusLabel, refresh);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    /** Best-effort, non-blocking - a failed fetch or unset/undecodable logo just leaves the header
     * without one rather than showing a broken image or an error. */
    private void loadRestaurantLogo() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                if (restaurant.logoImageBase64() == null || restaurant.logoImageBase64().isBlank()) {
                    return;
                }
                byte[] bytes = java.util.Base64.getDecoder().decode(restaurant.logoImageBase64());
                Image image = new Image(new java.io.ByteArrayInputStream(bytes));
                Platform.runLater(() -> {
                    logoView.setImage(image);
                    logoView.setManaged(true);
                    logoView.setVisible(true);
                });
            } catch (Exception ignored) {
                // Non-fatal - see this method's javadoc.
            }
        }, "chefpay-dashboard-logo-load");
        worker.setDaemon(true);
        worker.start();
    }

    public void reload() {
        statusLabel.setText("Loading...");
        loadRestaurantLogo();
        Thread configWorker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                dashboardViewMode = restaurant.dashboardViewMode();
            } catch (ApiException ignored) {
                // keep whatever mode was already in effect - see KitchenDisplayView's identical fallback
            }
            loadForMode();
        }, "chefpay-dashboard-config-load");
        configWorker.setDaemon(true);
        configWorker.start();
    }

    /** Fetches exactly what the current {@link #dashboardViewMode} needs - never both endpoints
     * when only one is being shown, and never neither. */
    private void loadForMode() {
        boolean wantCards = !"GRAPHICAL".equals(dashboardViewMode);
        boolean wantCharts = !"STANDARD".equals(dashboardViewMode);

        if (wantCards) {
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.get("/api/dashboard/summary");
                    DashboardDtos.SummaryDto summary = apiClient.convert(data, DashboardDtos.SummaryDto.class);
                    Platform.runLater(() -> renderSummary(summary));
                } catch (ApiException ex) {
                    Platform.runLater(() -> statusLabel.setText("Could not load dashboard: " + ex.getMessage()));
                }
            }, "chefpay-dashboard-load");
            worker.setDaemon(true);
            worker.start();
        } else {
            Platform.runLater(() -> { cards.getChildren().clear(); statusLabel.setText("Updated just now"); });
        }

        if (wantCharts) {
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.get("/api/dashboard/analytics");
                    DashboardDtos.AnalyticsDto analytics = apiClient.convert(data, DashboardDtos.AnalyticsDto.class);
                    Platform.runLater(() -> renderAnalytics(analytics));
                } catch (ApiException ex) {
                    Platform.runLater(() -> statusLabel.setText("Could not load charts: " + ex.getMessage()));
                }
            }, "chefpay-dashboard-analytics-load");
            worker.setDaemon(true);
            worker.start();
        } else {
            Platform.runLater(() -> chartsSection.getChildren().clear());
        }
    }

    private void renderSummary(DashboardDtos.SummaryDto summary) {
        statusLabel.setText("Updated just now");
        cards.getChildren().setAll(
                card("Today's Sales", "₹" + summary.todaySalesTotal(), "#27ae60"),
                card("Payments Today", String.valueOf(summary.todayPaymentCount()), "#2c3e50"),
                card("Orders Today", String.valueOf(summary.todayOrderCount()), "#2c3e50"),
                card("Open Orders", String.valueOf(summary.openOrderCount()), "#2980b9"),
                card("Tables Occupied", summary.occupiedTableCount() + " / " + summary.totalTableCount(), "#8e44ad"),
                card("Low Stock Items", String.valueOf(summary.lowStockItemCount()),
                        summary.lowStockItemCount() > 0 ? "#c0392b" : "#27ae60")
        );
    }

    /** Round 12 §5 - builds every chart/trend from one {@code AnalyticsDto} fetch. Each chart is
     * skipped (not shown as an empty frame) when its underlying list is empty - e.g. a brand-new
     * restaurant with no sales yet - rather than rendering a misleading blank chart. */
    private void renderAnalytics(DashboardDtos.AnalyticsDto analytics) {
        chartsSection.getChildren().clear();

        FlowPane statCards = new FlowPane(16, 16);
        statCards.getChildren().addAll(
                card("Discount Given Today", "₹" + analytics.discountTotalToday(), "#e67e22"),
                card("Tax Collected Today", "₹" + analytics.taxTotalToday(), "#2c3e50"),
                card("Avg Order Value Today", "₹" + analytics.averageOrderValueToday(), "#2980b9"),
                card("Inventory Value", "₹" + analytics.totalInventoryValue(), "#16a085")
        );
        chartsSection.getChildren().add(statCards);

        if (!analytics.salesTrend().isEmpty()) {
            chartsSection.getChildren().addAll(new Separator(), sectionTitle("Sales Trend (Last 7 Days)"), buildSalesTrendChart(analytics));
        }
        HBox row = new HBox(20);
        if (!analytics.categoryBreakdown().isEmpty()) {
            row.getChildren().add(buildCategoryChart(analytics));
        }
        if (!analytics.paymentMethods().isEmpty()) {
            row.getChildren().add(buildPaymentMethodChart(analytics));
        }
        if (!row.getChildren().isEmpty()) {
            chartsSection.getChildren().addAll(new Separator(), row);
        }
        if (!analytics.topItems().isEmpty()) {
            chartsSection.getChildren().addAll(new Separator(), sectionTitle("Top Selling Items Today"), buildTopItemsChart(analytics));
        }
        if (!analytics.branchSales().isEmpty() && analytics.branchSales().size() > 1) {
            // Only worth a chart of its own once there's more than one branch/bucket to compare -
            // a single-branch restaurant would just be reshowing "Today's Sales" from the KPI card.
            chartsSection.getChildren().addAll(new Separator(), sectionTitle("Branch-wise Sales Today"), buildBranchChart(analytics));
        }
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #444;");
        return label;
    }

    private LineChart<String, Number> buildSalesTrendChart(DashboardDtos.AnalyticsDto analytics) {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        LineChart<String, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setLegendVisible(false);
        chart.setPrefHeight(260);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (DashboardDtos.DailySalesPointDto point : analytics.salesTrend()) {
            series.getData().add(new XYChart.Data<>(point.date().toString(), point.total()));
        }
        chart.getData().add(series);
        return chart;
    }

    private BarChart<String, Number> buildCategoryChart(DashboardDtos.AnalyticsDto analytics) {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setTitle("Sales by Category (Today)");
        chart.setLegendVisible(false);
        chart.setPrefHeight(280);
        chart.setPrefWidth(420);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (DashboardDtos.CategorySalesDto c : analytics.categoryBreakdown()) {
            series.getData().add(new XYChart.Data<>(c.categoryName(), c.total()));
        }
        chart.getData().add(series);
        return chart;
    }

    private PieChart buildPaymentMethodChart(DashboardDtos.AnalyticsDto analytics) {
        PieChart chart = new PieChart();
        chart.setTitle("Payment Methods (Today)");
        chart.setPrefHeight(280);
        chart.setPrefWidth(380);
        for (DashboardDtos.PaymentMethodSalesDto m : analytics.paymentMethods()) {
            chart.getData().add(new PieChart.Data(m.method() + " (" + m.count() + ")", m.total().doubleValue()));
        }
        return chart;
    }

    private BarChart<String, Number> buildTopItemsChart(DashboardDtos.AnalyticsDto analytics) {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setLegendVisible(false);
        chart.setPrefHeight(260);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (DashboardDtos.TopItemDto item : analytics.topItems()) {
            series.getData().add(new XYChart.Data<>(item.itemName(), item.revenue()));
        }
        chart.getData().add(series);
        return chart;
    }

    private BarChart<String, Number> buildBranchChart(DashboardDtos.AnalyticsDto analytics) {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setLegendVisible(false);
        chart.setPrefHeight(260);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (DashboardDtos.BranchSalesDto b : analytics.branchSales()) {
            series.getData().add(new XYChart.Data<>(b.branchName(), b.total()));
        }
        chart.getData().add(series);
        return chart;
    }

    private VBox card(String label, String value, String accentColor) {
        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: " + accentColor + ";");
        Label labelLabel = new Label(label);
        labelLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        VBox box = new VBox(6, valueLabel, labelLabel);
        box.setPadding(new Insets(20, 28, 20, 28));
        box.setPrefWidth(220);
        box.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #e0e0e0; "
                + "-fx-border-radius: 10; -fx-border-width: 1; -fx-border-color: " + accentColor + "22;");
        return box;
    }

    public Parent view() {
        return root;
    }
}
