package com.chefpay.javafx.fraud;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.RuleConfigDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin screen for the Tier-1 fraud/loss-prevention rule thresholds (AI Backbone Addendum F1.5 -
 * "Store rule thresholds in a database-backed rule_config table, editable from an admin screen").
 * Rows are seeded once per {@code FraudRuleCode} by {@code DataSeeder} - this screen only ever
 * edits an existing row (see {@code RuleConfigController}'s javadoc for why creation/deletion isn't
 * exposed here).
 */
public class RuleConfigView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final ListView<RuleConfigDtos.RuleConfigDto> configList = new ListView<>();
    private final VBox detailBox = new VBox(12);
    private final Label statusLabel = new Label();

    private RuleConfigDtos.RuleConfigDto selected;

    public RuleConfigView(ApiClient apiClient) {
        this.apiClient = apiClient;
        root.setTop(buildHeader());
        root.setLeft(buildList());
        root.setCenter(buildDetailPanel());
        renderEmptyDetail("Select a rule on the left.");
    }

    private HBox buildHeader() {
        Label title = new Label("Fraud Rule Configuration");
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

    private VBox buildList() {
        configList.setPrefWidth(280);
        configList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(RuleConfigDtos.RuleConfigDto config, boolean empty) {
                super.updateItem(config, empty);
                if (empty || config == null) {
                    setText(null);
                } else {
                    setText((config.enabled() ? "" : "[OFF] ") + config.friendlyName() + "  •  " + config.severity());
                }
            }
        });
        configList.getSelectionModel().selectedItemProperty().addListener((obs, old, config) -> {
            if (config != null) {
                renderDetail(config);
            }
        });
        VBox box = new VBox(configList);
        box.setPadding(new Insets(16, 0, 16, 16));
        VBox.setVgrow(configList, Priority.ALWAYS);
        return box;
    }

    private ScrollPane buildDetailPanel() {
        detailBox.setPadding(new Insets(16, 24, 24, 24));
        ScrollPane scroll = new ScrollPane(detailBox);
        scroll.setFitToWidth(true);
        return scroll;
    }

    public void reload() {
        statusLabel.setText("Loading...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/fraud/rule-configs");
                List<RuleConfigDtos.RuleConfigDto> configs = apiClient.convertList(data, RuleConfigDtos.RuleConfigDto.class);
                Platform.runLater(() -> {
                    statusLabel.setText(configs.size() + " rule(s)");
                    String selectedCode = selected == null ? null : selected.ruleCode();
                    configList.getItems().setAll(configs);
                    configs.stream().filter(c -> c.ruleCode().equals(selectedCode)).findFirst()
                            .ifPresentOrElse(c -> configList.getSelectionModel().select(c),
                                    () -> renderEmptyDetail("Select a rule on the left."));
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load rule configs: " + ex.getMessage()));
            }
        }, "chefpay-rule-config-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderEmptyDetail(String message) {
        selected = null;
        detailBox.getChildren().setAll(new Label(message));
    }

    private void renderDetail(RuleConfigDtos.RuleConfigDto config) {
        this.selected = config;
        detailBox.getChildren().clear();

        Label header = new Label(config.friendlyName());
        header.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        detailBox.getChildren().add(header);

        Label description = new Label(config.description() == null ? "" : config.description());
        description.setWrapText(true);
        description.setStyle("-fx-text-fill: #666;");
        detailBox.getChildren().add(description);
        detailBox.getChildren().add(new Separator());

        detailBox.getChildren().add(infoRow("Rule Code", config.ruleCode()));
        detailBox.getChildren().add(infoRow("Threshold", config.thresholdValue().stripTrailingZeros().toPlainString()));
        if (config.secondaryThresholdValue() != null) {
            detailBox.getChildren().add(infoRow("Secondary Threshold", config.secondaryThresholdValue().stripTrailingZeros().toPlainString()));
        }
        if (config.windowMinutes() != null) {
            detailBox.getChildren().add(infoRow("Rolling Window", config.windowMinutes() + " minutes"));
        }
        detailBox.getChildren().add(infoRow("Severity", config.severity()));
        detailBox.getChildren().add(infoRow("Enabled", config.enabled() ? "Yes" : "No"));
        detailBox.getChildren().add(new Separator());

        Button edit = new Button("Edit");
        edit.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        edit.setOnAction(e -> editDialog(config));
        detailBox.getChildren().add(edit);
    }

    private HBox infoRow(String label, String value) {
        Label l = new Label(label);
        l.setStyle("-fx-text-fill: #666;");
        Label v = new Label(value == null ? "-" : value);
        v.setWrapText(true);
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, l, spacer, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void editDialog(RuleConfigDtos.RuleConfigDto config) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Edit - " + config.friendlyName());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField threshold = new TextField(config.thresholdValue().stripTrailingZeros().toPlainString());
        TextField secondary = new TextField(config.secondaryThresholdValue() == null ? ""
                : config.secondaryThresholdValue().stripTrailingZeros().toPlainString());
        secondary.setPromptText("Not used by this rule");
        // None of the MVP rule set actually populates a secondary threshold yet (see RuleConfig's
        // javadoc - MANAGER_PIN_OVERUSE's second check is a fixed same-minute-cross-terminal rule,
        // not a configurable number), so this field only lights up if a future rule seeds one.
        secondary.setDisable(config.secondaryThresholdValue() == null);
        TextField window = new TextField(config.windowMinutes() == null ? "" : config.windowMinutes().toString());
        window.setPromptText("Not used by this rule");
        window.setDisable(config.windowMinutes() == null);
        ChoiceBox<String> severity = new ChoiceBox<>(javafx.collections.FXCollections.observableArrayList(
                "LOW", "MEDIUM", "HIGH", "CRITICAL"));
        severity.setValue(config.severity());
        CheckBox enabled = new CheckBox("Enabled");
        enabled.setSelected(config.enabled());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        int row = 0;
        grid.addRow(row++, new Label("Threshold"), threshold);
        grid.addRow(row++, new Label("Secondary Threshold"), secondary);
        grid.addRow(row++, new Label("Rolling Window (minutes)"), window);
        grid.addRow(row++, new Label("Severity"), severity);
        grid.addRow(row, enabled);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> null);
        dialog.getDialogPane().lookupButton(saveType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            BigDecimal thresholdValue;
            BigDecimal secondaryValue = null;
            Integer windowValue = null;
            try {
                thresholdValue = new BigDecimal(threshold.getText().trim());
                if (!secondary.isDisabled() && !secondary.getText().isBlank()) {
                    secondaryValue = new BigDecimal(secondary.getText().trim());
                }
                if (!window.isDisabled() && !window.getText().isBlank()) {
                    windowValue = Integer.parseInt(window.getText().trim());
                }
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter valid numbers.").showAndWait();
                event.consume();
                return;
            }
            RuleConfigDtos.UpdateRuleConfigRequest request = new RuleConfigDtos.UpdateRuleConfigRequest(
                    thresholdValue, secondaryValue, windowValue, severity.getValue(), enabled.isSelected(), config.version());
            Thread worker = new Thread(() -> {
                try {
                    apiClient.patch("/api/fraud/rule-configs/" + config.id(), request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> {
                        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                            reload();
                        }
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    });
                }
            }, "chefpay-rule-config-save");
            worker.setDaemon(true);
            worker.start();
        });

        dialog.showAndWait();
    }

    public Parent view() {
        return root;
    }
}
