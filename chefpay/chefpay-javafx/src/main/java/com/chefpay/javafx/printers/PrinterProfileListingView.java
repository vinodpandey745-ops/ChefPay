package com.chefpay.javafx.printers;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.PrinterProfileDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Printer setup - Round 8's Printer Listing screen, the one screen in this batch with real CRUD
 * (the other three Round 8 screens are read-only monitoring/inbox views). Mirrors
 * {@code MenuManagementView}'s {@code Dialog<T>} + {@code GridPane} + {@code setResultConverter}
 * pattern and {@code TableManagementView}'s VERSION_CONFLICT handling. Create/Edit are gated on
 * {@code RESTAURANT_MANAGE}, matching the endpoint's own permission requirement - the server is the
 * real gate, this is only UX.
 */
public class PrinterProfileListingView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox rowsBox = new VBox(0);
    private final Label statusLabel = new Label();

    public PrinterProfileListingView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        rowsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Printer Setup");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("RESTAURANT_MANAGE")) {
            Button addProfile = new Button("+ New Printer Profile");
            addProfile.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addProfile.setOnAction(e -> newProfileDialog());
            header.getChildren().add(header.getChildren().size() - 1, addProfile);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/printer-profiles");
                List<PrinterProfileDtos.PrinterProfileDto> profiles =
                        apiClient.convertList(data, PrinterProfileDtos.PrinterProfileDto.class);
                Platform.runLater(() -> render(profiles));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load printer profiles: " + ex.getMessage()));
            }
        }, "chefpay-printer-profiles-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<PrinterProfileDtos.PrinterProfileDto> profiles) {
        statusLabel.setText(profiles.size() + " printer profile(s)");
        rowsBox.getChildren().clear();
        if (profiles.isEmpty()) {
            rowsBox.getChildren().add(new Label("No printer profiles yet - use \"+ New Printer Profile\" above to set one up."));
            return;
        }
        Label columnHeader = new Label(String.format("%-20s %-28s %-16s %-12s", "Name", "Printer Name", "Used For", "Status"));
        columnHeader.setStyle("-fx-font-family: monospace; -fx-font-weight: bold; -fx-text-fill: #888; -fx-font-size: 11px;");
        rowsBox.getChildren().add(columnHeader);
        for (PrinterProfileDtos.PrinterProfileDto profile : profiles) {
            rowsBox.getChildren().add(buildRow(profile));
        }
    }

    private HBox buildRow(PrinterProfileDtos.PrinterProfileDto profile) {
        Label name = new Label(profile.name());
        name.setPrefWidth(180);
        name.setStyle(profile.active() ? "" : "-fx-text-fill: #999;");

        Label printerName = new Label(profile.printerName());
        printerName.setPrefWidth(260);
        printerName.setStyle(profile.active() ? "" : "-fx-text-fill: #999;");

        Label usedFor = new Label(usedForTags(profile));
        usedFor.setPrefWidth(140);

        Label status = new Label(profile.active() ? "Active" : "Inactive");
        status.setPrefWidth(100);
        status.setStyle(profile.active() ? "-fx-text-fill: #27ae60;" : "-fx-text-fill: #c0392b;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, name, printerName, usedFor, status, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-border-color: #eee; -fx-border-width: 0 0 1 0;");

        if (SessionStore.get().hasPermission("RESTAURANT_MANAGE")) {
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editProfileDialog(profile));
            row.getChildren().add(edit);
        }
        return row;
    }

    private String usedForTags(PrinterProfileDtos.PrinterProfileDto profile) {
        List<String> tags = new java.util.ArrayList<>();
        if (profile.forBill()) {
            tags.add("Bill");
        }
        if (profile.forKot()) {
            tags.add("KOT");
        }
        if (profile.forEbill()) {
            tags.add("eBill");
        }
        return tags.isEmpty() ? "-" : String.join("/", tags);
    }

    private void newProfileDialog() {
        Dialog<PrinterProfileDtos.CreatePrinterProfileRequest> dialog = new Dialog<>();
        dialog.setTitle("New Printer Profile");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("e.g. Bills, KOT Printer");
        TextField printerName = new TextField();
        printerName.setPromptText("Exact OS print-queue name - see this computer's printer settings");
        CheckBox forBill = new CheckBox("For Bill");
        CheckBox forKot = new CheckBox("For KOT");
        CheckBox forEbill = new CheckBox("For eBill");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Printer Name"), printerName);
        grid.addRow(2, new Label(""), forBill);
        grid.addRow(3, new Label(""), forKot);
        grid.addRow(4, new Label(""), forEbill);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank() || printerName.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Name and printer name are required.").showAndWait();
                return null;
            }
            return new PrinterProfileDtos.CreatePrinterProfileRequest(null, name.getText().trim(),
                    printerName.getText().trim(), forBill.isSelected(), forKot.isSelected(), forEbill.isSelected());
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/printer-profiles", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-printer-profile-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editProfileDialog(PrinterProfileDtos.PrinterProfileDto profile) {
        Dialog<PrinterProfileDtos.UpdatePrinterProfileRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit - " + profile.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField name = new TextField(profile.name());
        TextField printerName = new TextField(profile.printerName());
        CheckBox forBill = new CheckBox("For Bill");
        forBill.setSelected(profile.forBill());
        CheckBox forKot = new CheckBox("For KOT");
        forKot.setSelected(profile.forKot());
        CheckBox forEbill = new CheckBox("For eBill");
        forEbill.setSelected(profile.forEbill());
        CheckBox active = new CheckBox("Active");
        active.setSelected(profile.active());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Printer Name"), printerName);
        grid.addRow(2, new Label(""), forBill);
        grid.addRow(3, new Label(""), forKot);
        grid.addRow(4, new Label(""), forEbill);
        grid.addRow(5, new Label(""), active);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            if (name.getText().isBlank() || printerName.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Name and printer name are required.").showAndWait();
                return null;
            }
            return new PrinterProfileDtos.UpdatePrinterProfileRequest(name.getText().trim(), printerName.getText().trim(),
                    forBill.isSelected(), forKot.isSelected(), forEbill.isSelected(), active.isSelected(), profile.version());
        });

        dialog.showAndWait().ifPresent(request -> updateProfile(profile, request));
    }

    private void updateProfile(PrinterProfileDtos.PrinterProfileDto profile, PrinterProfileDtos.UpdatePrinterProfileRequest request) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/printer-profiles/" + profile.id(), request);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING, "This printer profile changed elsewhere - showing the latest version. Please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-printer-profile-update");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
