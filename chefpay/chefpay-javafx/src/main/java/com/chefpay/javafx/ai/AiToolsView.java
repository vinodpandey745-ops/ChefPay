package com.chefpay.javafx.ai;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.AiDtos;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Round 10: one screen bringing together every AI feature that isn't already folded into an
 * existing screen (Feature E - AI menu descriptions - lives in {@code MenuManagementView}'s edit
 * dialog instead, since it's a one-field, one-item action). Reached from the Operations hub as
 * "AI Tools", gated on the {@code AI_USE} permission - every action here still separately requires
 * AI Features + a saved key + that specific feature's own switch to be on in Settings, enforced
 * server-side by {@code AiService.assertEnabled}; a user with access to this screen but nothing
 * configured yet just gets a clear "turn this on in Settings" message back from each button.
 */
public class AiToolsView {

    private final BorderPane rootWrapper;
    private final ApiClient apiClient;

    // ---- Feature A: Menu Import ----
    private final Label menuImportStatus = new Label();
    private final VBox draftRows = new VBox(8);
    private final List<DraftRow> currentDrafts = new ArrayList<>();
    private final Button applyDraftsButton = new Button("Apply Selected");

    // ---- Feature B: Ask Your Data ----
    private final TextField questionField = new TextField();
    private final DatePicker insightsFrom = new DatePicker();
    private final DatePicker insightsTo = new DatePicker();
    private final TextArea answerArea = new TextArea();

    // ---- Feature C: Reorder Drafts ----
    private final TextArea reorderDraftArea = new TextArea();
    private final Label reorderStatus = new Label();

    // ---- Feature D: Anomaly Scan ----
    private final DatePicker anomalyFrom = new DatePicker();
    private final DatePicker anomalyTo = new DatePicker();
    private final TextArea anomalyArea = new TextArea();

    // ---- Feature F: Nightly Summary ----
    private final TextArea nightlySummaryArea = new TextArea();

    private static final List<String> FOOD_TYPE_DISPLAY = List.of("Veg", "Egg", "Non-Veg");

    public AiToolsView(ApiClient apiClient) {
        this.apiClient = apiClient;
        this.rootWrapper = new BorderPane();

        Label title = new Label("AI Tools");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label subtitle = new Label("Configure your AI provider and turn features on/off in Settings first.");
        subtitle.setStyle("-fx-text-fill: #888; -fx-font-size: 12px;");
        VBox header = new VBox(2, title, subtitle);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        rootWrapper.setTop(header);

        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(
                new Tab("Menu Import", buildMenuImportTab()),
                new Tab("Ask Your Data", buildInsightsTab()),
                new Tab("Reorder Drafts", buildReorderTab()),
                new Tab("Anomaly Scan", buildAnomalyTab()),
                new Tab("Nightly Summary", buildNightlySummaryTab()));
        tabs.getTabs().forEach(t -> t.setClosable(false));
        rootWrapper.setCenter(tabs);
    }

    // ==================== Feature A: Menu Import ====================

    private ScrollPane buildMenuImportTab() {
        Label help = new Label(
                "Choose a photo of your menu (one page at a time works best). The AI reads it and drafts "
                        + "items below for you to review, edit, or remove before anything is actually added to "
                        + "your menu - nothing is saved until you press \"Apply Selected\".");
        help.setWrapText(true);
        help.setMaxWidth(640);
        help.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        Button chooseImage = new Button("Choose Menu Photo...");
        chooseImage.setOnAction(e -> chooseMenuPhoto(chooseImage.getScene() == null ? null : chooseImage.getScene().getWindow()));
        menuImportStatus.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        applyDraftsButton.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
        applyDraftsButton.setOnAction(e -> applyDrafts());
        applyDraftsButton.setDisable(true);

        Label draftsHeader = new Label("Draft items");
        draftsHeader.setStyle("-fx-font-weight: bold;");

        VBox content = new VBox(12, help, new HBox(12, chooseImage, menuImportStatus), new Separator(),
                draftsHeader, draftRows, applyDraftsButton);
        content.setPadding(new Insets(20));
        content.setMaxWidth(760);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private void chooseMenuPhoto(javafx.stage.Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a menu photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        menuImportStatus.setText("Reading photo with AI - this can take up to a minute...");
        draftRows.getChildren().clear();
        currentDrafts.clear();
        applyDraftsButton.setDisable(true);

        Thread worker = new Thread(() -> {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String lower = file.getName().toLowerCase();
                String mimeType = lower.endsWith(".png") ? "image/png" : "image/jpeg";
                var data = apiClient.post("/api/ai/menu-import/analyze", new AiDtos.AnalyzeRequest(base64, mimeType));
                AiDtos.AnalyzeResponse response = apiClient.convert(data, AiDtos.AnalyzeResponse.class);
                Platform.runLater(() -> renderDrafts(response.items()));
            } catch (Exception ex) {
                Platform.runLater(() -> menuImportStatus.setText("Could not read that photo: "
                        + (ex instanceof ApiException ae ? ae.getMessage() : ex.getMessage())));
            }
        }, "chefpay-ai-menu-import-analyze");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderDrafts(List<AiDtos.DraftItemDto> items) {
        draftRows.getChildren().clear();
        currentDrafts.clear();
        if (items.isEmpty()) {
            menuImportStatus.setText("No items were confidently read from that photo - try a clearer/closer photo.");
            applyDraftsButton.setDisable(true);
            return;
        }
        for (AiDtos.DraftItemDto item : items) {
            DraftRow row = new DraftRow(item);
            currentDrafts.add(row);
            draftRows.getChildren().add(row.box());
        }
        menuImportStatus.setText(items.size() + " item(s) drafted - review below, then Apply Selected.");
        applyDraftsButton.setDisable(false);
    }

    private void applyDrafts() {
        List<AiDtos.ApplyItemDto> toApply = new ArrayList<>();
        for (DraftRow row : currentDrafts) {
            if (!row.include.isSelected()) {
                continue;
            }
            try {
                BigDecimal price = new BigDecimal(row.price.getText().trim());
                toApply.add(new AiDtos.ApplyItemDto(row.name.getText().trim(), row.category.getText().trim(), price,
                        foodTypeCode(row.foodType.getValue()), row.description.getText().trim()));
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.WARNING, "\"" + row.name.getText() + "\" has an invalid price - fix it or uncheck it.").showAndWait();
                return;
            }
        }
        if (toApply.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Nothing selected to apply.").showAndWait();
            return;
        }
        applyDraftsButton.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/ai/menu-import/apply", new AiDtos.ApplyRequest(toApply));
                AiDtos.ApplyResponse response = apiClient.convert(data, AiDtos.ApplyResponse.class);
                Platform.runLater(() -> {
                    menuImportStatus.setText("Added " + response.itemsCreated() + " item(s)"
                            + (response.categoriesCreated() > 0 ? " and " + response.categoriesCreated() + " new categor"
                            + (response.categoriesCreated() == 1 ? "y" : "ies") : "") + " to your menu.");
                    draftRows.getChildren().clear();
                    currentDrafts.clear();
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    applyDraftsButton.setDisable(false);
                    new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                });
            }
        }, "chefpay-ai-menu-import-apply");
        worker.setDaemon(true);
        worker.start();
    }

    /** One editable draft row - the owner can fix anything the AI got wrong (or uncheck it) before Apply. */
    private static final class DraftRow {
        final CheckBox include = new CheckBox();
        final TextField name = new TextField();
        final TextField category = new TextField();
        final TextField price = new TextField();
        final ChoiceBox<String> foodType = new ChoiceBox<>(FXCollections.observableArrayList(FOOD_TYPE_DISPLAY));
        final TextField description = new TextField();
        private final HBox box;

        DraftRow(AiDtos.DraftItemDto item) {
            include.setSelected(true);
            name.setText(item.name());
            name.setPrefWidth(160);
            category.setText(item.categoryName());
            category.setPrefWidth(120);
            price.setText(item.price() == null ? "" : item.price().stripTrailingZeros().toPlainString());
            price.setPromptText("price");
            price.setPrefWidth(80);
            foodType.setValue(foodTypeDisplay(item.foodType()));
            description.setText(item.description() == null ? "" : item.description());
            description.setPrefWidth(220);
            box = new HBox(8, include, name, category, price, foodType, description);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        HBox box() {
            return box;
        }
    }

    private static String foodTypeCode(String display) {
        return switch (display) {
            case "Egg" -> "EGG";
            case "Non-Veg" -> "NON_VEG";
            default -> "VEG";
        };
    }

    private static String foodTypeDisplay(String code) {
        if ("EGG".equals(code)) {
            return "Egg";
        }
        if ("NON_VEG".equals(code)) {
            return "Non-Veg";
        }
        return "Veg";
    }

    // ==================== Feature B: Ask Your Data ====================

    private ScrollPane buildInsightsTab() {
        questionField.setPromptText("e.g. What were my top 3 sellers this week?");
        questionField.setPrefWidth(420);
        insightsFrom.setPromptText("From (optional)");
        insightsTo.setPromptText("To (optional)");
        Button ask = new Button("Ask");
        ask.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        ask.setOnAction(e -> askInsights());

        answerArea.setEditable(false);
        answerArea.setWrapText(true);
        answerArea.setPrefRowCount(10);

        HBox dateRow = new HBox(8, new Label("Date range (optional):"), insightsFrom, insightsTo);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        Label help = new Label("Defaults to the trailing 30 days if you leave the date range blank.");
        help.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        VBox content = new VBox(12, new HBox(8, questionField, ask), dateRow, help, answerArea);
        content.setPadding(new Insets(20));
        content.setMaxWidth(700);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private void askInsights() {
        if (questionField.getText() == null || questionField.getText().isBlank()) {
            return;
        }
        answerArea.setText("Thinking...");
        String question = questionField.getText().trim();
        LocalDate from = insightsFrom.getValue();
        LocalDate to = insightsTo.getValue();
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/ai/insights/chat", new AiDtos.ChatRequest(question, from, to));
                AiDtos.ChatResponse response = apiClient.convert(data, AiDtos.ChatResponse.class);
                Platform.runLater(() -> answerArea.setText(response.answer()));
            } catch (ApiException ex) {
                Platform.runLater(() -> answerArea.setText(ex.getMessage()));
            }
        }, "chefpay-ai-insights-chat");
        worker.setDaemon(true);
        worker.start();
    }

    // ==================== Feature C: Reorder Drafts ====================

    private ScrollPane buildReorderTab() {
        Label help = new Label("Drafts a ready-to-send supplier message covering everything currently at or "
                + "below its reorder threshold, sized to recent consumption where history is available.");
        help.setWrapText(true);
        help.setMaxWidth(640);
        help.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        Button generate = new Button("Generate Draft");
        generate.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        generate.setOnAction(e -> generateReorderDraft());
        Button copy = new Button("Copy to Clipboard");
        copy.setOnAction(e -> copyToClipboard(reorderDraftArea.getText()));
        reorderStatus.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        reorderDraftArea.setWrapText(true);
        reorderDraftArea.setPrefRowCount(10);

        VBox content = new VBox(12, help, new HBox(8, generate, copy, reorderStatus), reorderDraftArea);
        content.setPadding(new Insets(20));
        content.setMaxWidth(700);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private void generateReorderDraft() {
        reorderStatus.setText("Drafting...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/ai/ops/reorder-draft", java.util.Map.of());
                AiDtos.ReorderDraftResponse response = apiClient.convert(data, AiDtos.ReorderDraftResponse.class);
                Platform.runLater(() -> {
                    reorderDraftArea.setText(response.draftMessage());
                    reorderStatus.setText(response.itemCount() + " low-stock item(s) covered.");
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> reorderStatus.setText(ex.getMessage()));
            }
        }, "chefpay-ai-reorder-draft");
        worker.setDaemon(true);
        worker.start();
    }

    // ==================== Feature D: Anomaly Scan ====================

    private ScrollPane buildAnomalyTab() {
        Label help = new Label("Scans recent voids, discounts, complimentary items and cancellations in the "
                + "Audit Log for unusual patterns worth a manager's attention.");
        help.setWrapText(true);
        help.setMaxWidth(640);
        help.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        anomalyFrom.setPromptText("From (optional)");
        anomalyTo.setPromptText("To (optional)");
        Button scan = new Button("Run Scan");
        scan.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        scan.setOnAction(e -> runAnomalyScan());

        anomalyArea.setEditable(false);
        anomalyArea.setWrapText(true);
        anomalyArea.setPrefRowCount(10);

        HBox dateRow = new HBox(8, new Label("Date range (optional):"), anomalyFrom, anomalyTo, scan);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        Label rangeHelp = new Label("Defaults to the trailing 7 days if you leave the date range blank.");
        rangeHelp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        VBox content = new VBox(12, help, dateRow, rangeHelp, anomalyArea);
        content.setPadding(new Insets(20));
        content.setMaxWidth(700);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private void runAnomalyScan() {
        anomalyArea.setText("Scanning...");
        LocalDate from = anomalyFrom.getValue();
        LocalDate to = anomalyTo.getValue();
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/ai/ops/anomaly-scan", new AiDtos.AnomalyScanRequest(from, to));
                AiDtos.AnomalyScanResponse response = apiClient.convert(data, AiDtos.AnomalyScanResponse.class);
                Platform.runLater(() -> anomalyArea.setText(response.summary()
                        + "\n\n(" + response.entriesScanned() + " entries scanned, " + response.from() + " to " + response.to() + ")"));
            } catch (ApiException ex) {
                Platform.runLater(() -> anomalyArea.setText(ex.getMessage()));
            }
        }, "chefpay-ai-anomaly-scan");
        worker.setDaemon(true);
        worker.start();
    }

    // ==================== Feature F: Nightly Summary ====================

    private ScrollPane buildNightlySummaryTab() {
        Label help = new Label("Automatically posts a short AI recap of the day's sales to your Alerts inbox "
                + "every night at 11:30 PM once this feature is on in Settings. Use \"Generate Now\" to see "
                + "what it looks like without waiting.");
        help.setWrapText(true);
        help.setMaxWidth(640);
        help.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        Button generate = new Button("Generate Now");
        generate.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        generate.setOnAction(e -> generateNightlySummary());

        nightlySummaryArea.setEditable(false);
        nightlySummaryArea.setWrapText(true);
        nightlySummaryArea.setPrefRowCount(8);

        VBox content = new VBox(12, help, generate, nightlySummaryArea);
        content.setPadding(new Insets(20));
        content.setMaxWidth(700);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private void generateNightlySummary() {
        nightlySummaryArea.setText("Generating today's summary...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/ai/nightly-summary/generate-now", java.util.Map.of());
                AiDtos.GenerateNowResponse response = apiClient.convert(data, AiDtos.GenerateNowResponse.class);
                Platform.runLater(() -> nightlySummaryArea.setText(response.summary()
                        + "\n\n(Also posted to your Alerts inbox.)"));
            } catch (ApiException ex) {
                Platform.runLater(() -> nightlySummaryArea.setText(ex.getMessage()));
            }
        }, "chefpay-ai-nightly-summary");
        worker.setDaemon(true);
        worker.start();
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    public void reload() {
        // Nothing to pre-fetch - every tab is action-driven (button press), matching a "tool" screen
        // rather than a live data view.
    }

    public Parent view() {
        return rootWrapper;
    }
}
