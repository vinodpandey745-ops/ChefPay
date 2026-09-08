package com.chefpay.javafx.purchasing;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.SupplierDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

/**
 * Supplier directory - Round 12 §12. Plain offline/manual contact records (see {@code
 * Supplier}'s and {@code SupplierChannel}'s javadoc for why there's no API-integration surface
 * here). {@code SUPPLIER_VIEW} to browse, {@code SUPPLIER_MANAGE} to add/edit - same split
 * {@code InventoryView} already uses for VIEW vs MANAGE.
 */
public class SupplierManagementView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final ListView<SupplierDtos.SupplierDto> supplierList = new ListView<>();
    private final Label statusLabel = new Label();

    public SupplierManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;
        root.setTop(buildHeader());
        root.setCenter(buildList());
    }

    private HBox buildHeader() {
        Label title = new Label("Suppliers");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());
        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("SUPPLIER_MANAGE")) {
            Button add = new Button("+ New Supplier");
            add.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            add.setOnAction(e -> newSupplierDialog());
            header.getChildren().add(header.getChildren().size() - 1, add);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private VBox buildList() {
        supplierList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SupplierDtos.SupplierDto s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String contact = (s.contactPerson() == null ? "" : s.contactPerson())
                        + (s.phone() == null ? "" : "  •  " + s.phone());
                setText(s.name() + (contact.isBlank() ? "" : "\n" + contact));
                if (SessionStore.get().hasPermission("SUPPLIER_MANAGE")) {
                    Button edit = new Button("Edit");
                    edit.setOnAction(e -> editSupplierDialog(s));
                    setGraphic(edit);
                } else {
                    setGraphic(null);
                }
            }
        });
        VBox box = new VBox(supplierList);
        box.setPadding(new Insets(16, 24, 24, 24));
        VBox.setVgrow(supplierList, Priority.ALWAYS);
        return box;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/suppliers");
                List<SupplierDtos.SupplierDto> suppliers = apiClient.convertList(data, SupplierDtos.SupplierDto.class);
                Platform.runLater(() -> {
                    statusLabel.setText(suppliers.size() + " supplier(s)");
                    supplierList.getItems().setAll(suppliers);
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load suppliers: " + ex.getMessage()));
            }
        }, "chefpay-supplier-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void newSupplierDialog() {
        SupplierDtos.CreateSupplierRequest result = supplierForm(null);
        if (result == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                apiClient.post("/api/suppliers", result);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
            }
        }, "chefpay-supplier-create");
        worker.setDaemon(true);
        worker.start();
    }

    private void editSupplierDialog(SupplierDtos.SupplierDto existing) {
        SupplierDtos.CreateSupplierRequest result = supplierForm(existing);
        if (result == null) {
            return;
        }
        var request = new SupplierDtos.UpdateSupplierRequest(result.name(), result.contactPerson(), result.phone(),
                result.email(), result.address(), result.notes(), null, existing.version());
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/suppliers/" + existing.id(), request);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
            }
        }, "chefpay-supplier-update");
        worker.setDaemon(true);
        worker.start();
    }

    private SupplierDtos.CreateSupplierRequest supplierForm(SupplierDtos.SupplierDto existing) {
        Dialog<SupplierDtos.CreateSupplierRequest> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "New Supplier" : "Edit Supplier");
        ButtonType okType = new ButtonType(existing == null ? "Create" : "Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField contact = new TextField(existing == null ? "" : nullToEmpty(existing.contactPerson()));
        TextField phone = new TextField(existing == null ? "" : nullToEmpty(existing.phone()));
        TextField email = new TextField(existing == null ? "" : nullToEmpty(existing.email()));
        TextField address = new TextField(existing == null ? "" : nullToEmpty(existing.address()));
        TextField notes = new TextField(existing == null ? "" : nullToEmpty(existing.notes()));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Contact Person"), contact);
        grid.addRow(2, new Label("Phone"), phone);
        grid.addRow(3, new Label("Email"), email);
        grid.addRow(4, new Label("Address"), address);
        grid.addRow(5, new Label("Notes"), notes);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != okType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Name is required.").showAndWait();
                return null;
            }
            return new SupplierDtos.CreateSupplierRequest(name.getText().trim(), blankToNull(contact.getText()),
                    blankToNull(phone.getText()), blankToNull(email.getText()), blankToNull(address.getText()), blankToNull(notes.getText()));
        });

        return dialog.showAndWait().orElse(null);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Parent view() {
        return root;
    }
}
