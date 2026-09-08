package com.chefpay.javafx.eod;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.EodDtos;
import com.chefpay.javafx.common.ReceiptPrinter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The guided End-of-Day wizard (AI Backbone Addendum F1.1-F1.7): Ingest -&gt; Blind Cash Count -&gt;
 * Fraud &amp; Audit Review -&gt; Finalize &amp; GL Sync. Renders whichever step {@link EodDtos.SessionDto
 * #status} says, same "the server's status IS the resume point" discipline {@code EodSession}'s
 * own javadoc describes - there is no separate client-side wizard-step tracking, this view just
 * re-fetches and re-renders on every action, matching every other screen in this app.
 */
public class EodWizardView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final DatePicker businessDatePicker = new DatePicker(LocalDate.now());
    private final Label statusLabel = new Label();
    private final VBox stepBox = new VBox(16);

    private EodDtos.SessionDto session;

    public EodWizardView(ApiClient apiClient) {
        this.apiClient = apiClient;
        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(stepBox);
        scroll.setFitToWidth(true);
        stepBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
        renderEmpty("Pick a business date and click Start / Resume Session.");
    }

    private HBox buildHeader() {
        Label title = new Label("End of Day");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        businessDatePicker.setPrefWidth(150);
        Button start = new Button("Start / Resume Session");
        start.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        start.setOnAction(e -> startOrResume());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> { if (session != null) reload(session.id()); });

        HBox header = new HBox(12, title, new Label("Business Date:"), businessDatePicker, start, spacer, statusLabel, refresh);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private void renderEmpty(String message) {
        stepBox.getChildren().setAll(new Label(message));
    }

    // ---- loading ----

    public void startOrResume() {
        LocalDate date = businessDatePicker.getValue() == null ? LocalDate.now() : businessDatePicker.getValue();
        statusLabel.setText("Loading...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/eod/sessions", new EodDtos.StartSessionRequest(date));
                EodDtos.SessionDto dto = apiClient.convert(data, EodDtos.SessionDto.class);
                Platform.runLater(() -> { this.session = dto; render(); });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not start/resume EOD session: " + ex.getMessage()));
            }
        }, "chefpay-eod-start");
        worker.setDaemon(true);
        worker.start();
    }

    private void reload(UUID sessionId) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/eod/sessions/" + sessionId);
                EodDtos.SessionDto dto = apiClient.convert(data, EodDtos.SessionDto.class);
                Platform.runLater(() -> { this.session = dto; render(); });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not reload EOD session: " + ex.getMessage()));
            }
        }, "chefpay-eod-reload");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- rendering ----

    private void render() {
        statusLabel.setText(session.businessDate() + "  •  " + session.status());
        stepBox.getChildren().clear();
        switch (session.status()) {
            case "INGESTING" -> stepBox.getChildren().add(buildIngestStep());
            case "CASH_COUNT" -> stepBox.getChildren().add(buildCashCountStep());
            case "REVIEW" -> {
                stepBox.getChildren().add(buildCashCountSummary());
                stepBox.getChildren().add(buildReviewStep());
            }
            case "FINALIZED" -> stepBox.getChildren().add(buildFinalizedStep());
            default -> stepBox.getChildren().add(new Label("This session is " + session.status() + "."));
        }
    }

    // ---- Step 1: Ingest ----

    private VBox buildIngestStep() {
        VBox box = new VBox(12);
        Label header = new Label("Step 1 of 4 - Channel Ingestion");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        box.getChildren().add(header);

        boolean anyPending = false;
        for (EodDtos.ChannelIngestionDto channel : session.channels()) {
            box.getChildren().add(channelRow(channel));
            if (channel.status().equals("PENDING")) {
                anyPending = true;
            }
        }

        if (anyPending) {
            Label note = new Label("One or more channels are still pending - enter their settlement before advancing, "
                    + "or advance anyway if that channel genuinely has nothing to reconcile today.");
            note.setWrapText(true);
            note.setStyle("-fx-text-fill: #b8860b;");
            box.getChildren().add(note);
        }

        Button advance = new Button("Advance to Cash Count");
        advance.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        advance.setOnAction(e -> run("/api/eod/sessions/" + session.id() + "/advance-to-cash-count", null));
        box.getChildren().add(advance);
        return box;
    }

    private HBox channelRow(EodDtos.ChannelIngestionDto channel) {
        Label name = new Label(channel.channelName());
        name.setPrefWidth(140);
        name.setStyle("-fx-font-weight: bold;");
        Label status = new Label(channel.status());
        status.setPrefWidth(140);
        status.setStyle(switch (channel.status()) {
            case "SUCCESS" -> "-fx-text-fill: #27ae60;";
            case "FAILED" -> "-fx-text-fill: #c0392b;";
            case "NOT_CONFIGURED" -> "-fx-text-fill: #999;";
            default -> "-fx-text-fill: #b8860b;";
        });
        Label amount = new Label(channel.amountReported() == null ? "-" : channel.amountReported().toPlainString());
        amount.setPrefWidth(120);

        HBox row = new HBox(12, name, status, amount);
        row.setAlignment(Pos.CENTER_LEFT);

        if ("AGGREGATOR".equals(channel.channelType()) && "PENDING".equals(channel.status())) {
            Button enter = new Button("Enter Settlement");
            enter.setOnAction(e -> settlementDialog(channel));
            row.getChildren().add(enter);
        } else if (channel.settlement() != null) {
            EodDtos.AggregatorSettlementDto s = channel.settlement();
            Label variance = new Label("Variance: " + s.variancePercent() + "%" + (s.flagged() ? "  [FLAGGED]" : ""));
            variance.setStyle(s.flagged() ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;" : "-fx-text-fill: #666;");
            row.getChildren().add(variance);
        }
        return row;
    }

    private void settlementDialog(EodDtos.ChannelIngestionDto channel) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Aggregator Settlement - " + channel.channelName());
        ButtonType saveType = new ButtonType("Submit", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField posTotal = new TextField();
        posTotal.setPromptText("What the POS shows for this channel today");
        TextField settledTotal = new TextField();
        settledTotal.setPromptText("What the aggregator's settlement report shows");
        TextField commission = new TextField();
        commission.setPromptText("Optional");
        TextField notes = new TextField();
        notes.setPromptText("Optional");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("POS-Recorded Total"), posTotal);
        grid.addRow(1, new Label("Aggregator-Reported Total"), settledTotal);
        grid.addRow(2, new Label("Commission"), commission);
        grid.addRow(3, new Label("Notes"), notes);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> null);
        dialog.getDialogPane().lookupButton(saveType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            BigDecimal pos;
            BigDecimal settled;
            BigDecimal commissionValue = null;
            try {
                pos = new BigDecimal(posTotal.getText().trim());
                settled = new BigDecimal(settledTotal.getText().trim());
                if (!commission.getText().isBlank()) {
                    commissionValue = new BigDecimal(commission.getText().trim());
                }
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter valid numbers.").showAndWait();
                event.consume();
                return;
            }
            String notesText = notes.getText() == null || notes.getText().isBlank() ? null : notes.getText().trim();
            run("/api/eod/sessions/" + session.id() + "/aggregator-settlement",
                    new EodDtos.SubmitAggregatorSettlementRequest(channel.id(), pos, settled, commissionValue, notesText));
        });
        dialog.showAndWait();
    }

    // ---- Step 2: Blind Cash Count ----

    private VBox buildCashCountStep() {
        VBox box = new VBox(12);
        Label header = new Label("Step 2 of 4 - Blind Cash Count");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        box.getChildren().add(header);
        Label note = new Label("Enter what was physically counted in the drawer, by denomination. The system total is "
                + "withheld until after you submit - this is what keeps the count \"blind\".");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #666;");
        box.getChildren().add(note);

        VBox rows = new VBox(8);
        record Row(TextField value, TextField count) {
        }
        List<Row> rowList = new ArrayList<>();
        Runnable[] addRowHolder = new Runnable[1];
        addRowHolder[0] = () -> {
            TextField value = new TextField();
            value.setPromptText("Denomination value");
            value.setPrefWidth(120);
            TextField count = new TextField();
            count.setPromptText("Count");
            count.setPrefWidth(80);
            Button remove = new Button("✕");
            HBox row = new HBox(8, new Label("Value:"), value, new Label("Count:"), count, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            Row r = new Row(value, count);
            remove.setOnAction(e -> { rows.getChildren().remove(row); rowList.remove(r); });
            rowList.add(r);
            rows.getChildren().add(row);
        };
        addRowHolder[0].run();

        Button addRow = new Button("+ Add Denomination");
        addRow.setOnAction(e -> addRowHolder[0].run());
        box.getChildren().add(rows);
        box.getChildren().add(addRow);

        Button submit = new Button("Submit Cash Count");
        submit.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        submit.setOnAction(e -> {
            List<EodDtos.DenominationLine> lines = new ArrayList<>();
            try {
                for (Row r : rowList) {
                    if (r.value().getText().isBlank() || r.count().getText().isBlank()) {
                        continue;
                    }
                    lines.add(new EodDtos.DenominationLine(new BigDecimal(r.value().getText().trim()),
                            Integer.parseInt(r.count().getText().trim())));
                }
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter valid numbers for every denomination row.").showAndWait();
                return;
            }
            if (lines.isEmpty()) {
                new Alert(Alert.AlertType.ERROR, "Enter at least one denomination.").showAndWait();
                return;
            }
            run("/api/eod/sessions/" + session.id() + "/cash-count", new EodDtos.SubmitCashCountRequest(lines));
        });
        box.getChildren().add(submit);
        return box;
    }

    /** Shown on the Review step (not Cash Count) - {@code EodService#submitCashCount} always
     * advances the session straight to REVIEW regardless of variance, so a HIGH_VARIANCE_FLAGGED
     * count is only ever seen here, not while still on the Cash Count step. Finalize is blocked
     * until this is either overridden or the count is resubmitted without a flag. */
    private VBox cashVarianceBanner() {
        EodDtos.CashCountDto cc = session.cashCount();
        VBox banner = new VBox(6);
        banner.setPadding(new Insets(10));
        banner.setStyle("-fx-background-color: #fdecea; -fx-border-color: #c0392b; -fx-border-radius: 4; -fx-background-radius: 4;");
        Label label = new Label("High variance flagged: physical " + cc.physicalTotal() + " vs expected " + cc.expectedTotal()
                + " (variance " + cc.variance() + "). A Level-3 PIN override is required before this can be finalized.");
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #c0392b;");
        Button override = new Button("Override with PIN");
        override.setOnAction(e -> pinAndReasonDialog("Override High Variance", (pin, reason) ->
                run("/api/eod/sessions/" + session.id() + "/cash-count/override", new EodDtos.OverrideCashCountRequest(pin, reason, null))));
        banner.getChildren().addAll(label, override);
        return banner;
    }

    // ---- Step 3: Review ----

    private VBox buildCashCountSummary() {
        VBox box = new VBox(6);
        Label header = new Label("Cash Drawer");
        header.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        box.getChildren().add(header);
        EodDtos.CashCountDto cc = session.cashCount();
        if (cc != null) {
            box.getChildren().add(new Label("Physical: " + cc.physicalTotal() + "   Expected: " + cc.expectedTotal()
                    + "   Variance: " + cc.variance() + "   Status: " + cc.status()
                    + (cc.overriddenByName() == null ? "" : "  (overridden by " + cc.overriddenByName() + ")")));
            if ("HIGH_VARIANCE_FLAGGED".equals(cc.status()) && cc.overriddenByName() == null) {
                box.getChildren().add(cashVarianceBanner());
            }
        }
        return box;
    }

    private VBox buildReviewStep() {
        VBox box = new VBox(12);
        Label header = new Label("Step 3 of 4 - Fraud & Audit Review");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        box.getChildren().add(header);

        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/eod/sessions/" + session.id() + "/anomalies");
                List<EodDtos.AnomalyDto> anomalies = apiClient.convertList(data, EodDtos.AnomalyDto.class);
                Platform.runLater(() -> renderAnomalies(box, anomalies));
            } catch (ApiException ex) {
                Platform.runLater(() -> box.getChildren().add(new Label("Could not load anomalies: " + ex.getMessage())));
            }
        }, "chefpay-eod-anomalies");
        worker.setDaemon(true);
        worker.start();

        Button finalizeBtn = new Button("Finalize & Generate Z-Report");
        finalizeBtn.setStyle("-fx-background-color: #16a085; -fx-text-fill: white; -fx-font-weight: bold;");
        finalizeBtn.setOnAction(e -> finalizeSession(null, null));
        box.getChildren().add(finalizeBtn);
        return box;
    }

    /** {@link #buildReviewStep} always adds the header label, then (synchronously) the Finalize
     * button, before this async callback can run - so index 1 (right after the header, right before
     * Finalize) is always the correct insertion point regardless of how long the anomaly fetch took. */
    private void renderAnomalies(VBox box, List<EodDtos.AnomalyDto> anomalies) {
        int insertAt = Math.min(1, box.getChildren().size());
        if (anomalies.isEmpty()) {
            box.getChildren().add(insertAt, new Label("No anomalies flagged for this business date."));
            return;
        }
        VBox list = new VBox(8);
        for (EodDtos.AnomalyDto a : anomalies) {
            list.getChildren().add(anomalyRow(a));
        }
        box.getChildren().add(insertAt, list);
    }

    private VBox anomalyRow(EodDtos.AnomalyDto a) {
        VBox row = new VBox(4);
        row.setPadding(new Insets(10));
        String color = switch (a.severity()) {
            case "CRITICAL" -> "#c0392b";
            case "HIGH" -> "#e67e22";
            case "MEDIUM" -> "#b8860b";
            default -> "#999";
        };
        row.setStyle("-fx-background-color: white; -fx-border-color: " + color + "; -fx-border-width: 0 0 0 4; -fx-padding: 10;");

        Label title = new Label("[" + a.severity() + "] " + a.category() + "  •  " + a.status());
        title.setStyle("-fx-font-weight: bold;");
        Label desc = new Label(a.description());
        desc.setWrapText(true);
        row.getChildren().addAll(title, desc);
        if (a.involvedUserName() != null) {
            row.getChildren().add(new Label("Involved: " + a.involvedUserName()));
        }
        if (a.amountImpact() != null) {
            row.getChildren().add(new Label("Amount impact: " + a.amountImpact()));
        }
        if ("UNREVIEWED".equals(a.status())) {
            Button resolve = new Button("Resolve");
            resolve.setOnAction(e -> resolveDialog(a));
            row.getChildren().add(resolve);
        } else {
            row.getChildren().add(new Label("Resolution: " + a.resolutionType()
                    + (a.resolutionNote() == null ? "" : " - " + a.resolutionNote())));
        }
        return row;
    }

    private void resolveDialog(EodDtos.AnomalyDto anomaly) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Resolve Anomaly");
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        ChoiceBox<String> resolutionType = new ChoiceBox<>(javafx.collections.FXCollections.observableArrayList(
                "LEGITIMATE_ERROR", "CONFIRMED_THEFT", "FALSE_POSITIVE"));
        resolutionType.getSelectionModel().selectFirst();
        ChoiceBox<String> escalationReason = new ChoiceBox<>(javafx.collections.FXCollections.observableArrayList(
                "EMPLOYEE_THEFT", "UNVERIFIED_VOID", "POLICY_ABUSE", "OTHER"));
        escalationReason.setDisable(true);
        resolutionType.getSelectionModel().selectedItemProperty().addListener((obs, old, val) ->
                escalationReason.setDisable(!"CONFIRMED_THEFT".equals(val)));
        TextArea note = new TextArea();
        note.setPrefRowCount(3);
        note.setPromptText("Note (optional, required when confirming fraud)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Resolution"), resolutionType);
        grid.addRow(1, new Label("Escalation Reason"), escalationReason);
        grid.addRow(2, new Label("Note"), note);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> null);
        dialog.getDialogPane().lookupButton(saveType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if ("CONFIRMED_THEFT".equals(resolutionType.getValue()) && escalationReason.getValue() == null) {
                new Alert(Alert.AlertType.ERROR, "Pick an escalation reason when confirming fraud.").showAndWait();
                event.consume();
                return;
            }
            String noteText = note.getText() == null || note.getText().isBlank() ? null : note.getText().trim();
            run("/api/eod/anomalies/" + anomaly.id() + "/resolve",
                    new EodDtos.ResolveAnomalyRequest(resolutionType.getValue(), escalationReason.getValue(), noteText));
        });
        dialog.showAndWait();
    }

    private void finalizeSession(String overridePin, String overrideReason) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/eod/sessions/" + session.id() + "/finalize",
                        new EodDtos.FinalizeRequest(overridePin, overrideReason, null));
                EodDtos.FinalizeResultDto result = apiClient.convert(data, EodDtos.FinalizeResultDto.class);
                Platform.runLater(() -> reload(result.sessionId()));
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("EOD_FINALIZE_BLOCKED".equals(ex.getErrorCode())) {
                        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, ex.getMessage()
                                + "\n\nEnter a Level-3 PIN and reason to force-finalize anyway?");
                        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                                pinAndReasonDialog("Force-Finalize", this::finalizeSession));
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-eod-finalize");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- Step 4: Finalized ----

    private VBox buildFinalizedStep() {
        VBox box = new VBox(12);
        Label header = new Label("Step 4 of 4 - Finalized");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        box.getChildren().add(header);

        if (session.finalizedWithOverride()) {
            Label override = new Label("Finalized with Level-3 override: " + session.finalizeOverrideReason());
            override.setWrapText(true);
            override.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
            box.getChildren().add(override);
        }
        box.getChildren().add(new Label("Finalized at: " + (session.finalizedAt() == null ? "-"
                : session.finalizedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a")))));
        box.getChildren().add(new Label("GL Sync: " + session.glSyncStatus()
                + (session.glSyncMessage() == null ? "" : " - " + session.glSyncMessage())));

        HBox actions = new HBox(10);
        Button viewText = new Button("View Z-Report");
        viewText.setOnAction(e -> ReceiptPrinter.show("Z-Report - " + session.businessDate(), session.zReportText()));
        actions.getChildren().add(viewText);
        if (session.zReportPdfAvailable()) {
            Button savePdf = new Button("Save Z-Report PDF");
            savePdf.setOnAction(e -> savePdf());
            actions.getChildren().add(savePdf);
        }
        box.getChildren().add(actions);
        return box;
    }

    private void savePdf() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Z-Report PDF");
        chooser.setInitialFileName("z-report-" + session.businessDate() + ".pdf");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File file = chooser.showSaveDialog(root.getScene().getWindow());
        if (file == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                byte[] pdf = apiClient.getBytes("/api/eod/sessions/" + session.id() + "/z-report.pdf");
                Files.write(file.toPath(), pdf);
                Platform.runLater(() -> statusLabel.setText("Z-Report saved to " + file.getName()));
            } catch (ApiException | IOException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not save PDF: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-eod-pdf");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- shared helpers ----

    private void run(String path, Object body) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.post(path, body);
                Platform.runLater(() -> reload(session.id()));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
            }
        }, "chefpay-eod-action");
        worker.setDaemon(true);
        worker.start();
    }

    /** Shared Level-3 (EOD_OVERRIDE) step-up prompt - PIN + a required reason - used by both the
     * high-variance cash-count override and the force-finalize path, matching {@code CashCount
     * #overrideBy}/{@code EodSession#finalizeOverrideReason}'s identical "PIN verified server-side,
     * reason always logged" design. */
    private void pinAndReasonDialog(String title, java.util.function.BiConsumer<String, String> onConfirm) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(title);
        ButtonType okType = new ButtonType("Confirm", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        PasswordField pin = new PasswordField();
        pin.setPromptText("Level-3 PIN");
        TextField reason = new TextField();
        reason.setPromptText("Reason (required)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("PIN"), pin);
        grid.addRow(1, new Label("Reason"), reason);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> null);
        dialog.getDialogPane().lookupButton(okType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (pin.getText() == null || pin.getText().isBlank() || reason.getText() == null || reason.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "PIN and reason are both required.").showAndWait();
                event.consume();
                return;
            }
            onConfirm.accept(pin.getText().trim(), reason.getText().trim());
        });
        dialog.showAndWait();
    }

    public Parent view() {
        return root;
    }
}
