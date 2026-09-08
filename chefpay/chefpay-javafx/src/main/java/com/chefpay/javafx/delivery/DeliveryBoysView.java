package com.chefpay.javafx.delivery;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.DeliveryBoyDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

/**
 * Delivery rider roster CRUD (Round 9) - backs the assignment dropdown {@code OnlineOrdersView}
 * shows when {@code Restaurant.deliveryBoyFeatureEnabled} is on. Deliberately the smallest
 * possible admin screen, structured exactly like {@code AreaManagementView}: a flat row list, a
 * create dialog, and an edit dialog with the same optimistic-lock {@code VERSION_CONFLICT}
 * handling every other admin screen in this app uses. Read is gated on {@code DELIVERY_MANAGE}
 * implicitly (the server also allows {@code ORDER_MODIFY} to read, for the assignment dropdown -
 * see {@code DeliveryBoyController}), writes are gated on {@code DELIVERY_MANAGE} - this screen's
 * actual write permission.
 */
public class DeliveryBoysView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox rowsBox = new VBox(8);
    private final Label statusLabel = new Label();

    public DeliveryBoysView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        rowsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Delivery Boys");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("DELIVERY_MANAGE")) {
            Button addDeliveryBoy = new Button("+ New Delivery Boy");
            addDeliveryBoy.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addDeliveryBoy.setOnAction(e -> newDeliveryBoyDialog());
            header.getChildren().add(header.getChildren().size() - 1, addDeliveryBoy);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/delivery-boys");
                List<DeliveryBoyDtos.DeliveryBoyDto> deliveryBoys = apiClient.convertList(data, DeliveryBoyDtos.DeliveryBoyDto.class);
                Platform.runLater(() -> render(deliveryBoys));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load delivery boys: " + ex.getMessage()));
            }
        }, "chefpay-delivery-boys-admin-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<DeliveryBoyDtos.DeliveryBoyDto> deliveryBoys) {
        statusLabel.setText(deliveryBoys.size() + " delivery boy(s)");
        rowsBox.getChildren().clear();
        if (deliveryBoys.isEmpty()) {
            rowsBox.getChildren().add(new Label("No delivery boys yet - use \"+ New Delivery Boy\" above to add one."));
            return;
        }
        for (DeliveryBoyDtos.DeliveryBoyDto deliveryBoy : deliveryBoys) {
            rowsBox.getChildren().add(buildDeliveryBoyRow(deliveryBoy));
        }
    }

    private HBox buildDeliveryBoyRow(DeliveryBoyDtos.DeliveryBoyDto deliveryBoy) {
        Label name = new Label(deliveryBoy.name());
        name.setStyle("-fx-font-size: 14px;" + (deliveryBoy.active() ? "" : " -fx-text-fill: #999;"));
        name.setPrefWidth(240);

        Label phone = new Label(deliveryBoy.phone() == null ? "-" : deliveryBoy.phone());
        phone.setPrefWidth(160);

        Label status = new Label(deliveryBoy.active() ? "" : "INACTIVE");
        status.setStyle("-fx-text-fill: #c0392b; -fx-font-size: 11px; -fx-font-weight: bold;");
        status.setPrefWidth(90);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, name, phone, status, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 12));

        if (SessionStore.get().hasPermission("DELIVERY_MANAGE")) {
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editDeliveryBoyDialog(deliveryBoy));
            row.getChildren().add(edit);
        }
        return row;
    }

    private void newDeliveryBoyDialog() {
        Dialog<DeliveryBoyDtos.CreateDeliveryBoyRequest> dialog = new Dialog<>();
        dialog.setTitle("New Delivery Boy");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("e.g. Ramesh Kumar");
        TextField phone = new TextField();
        phone.setPromptText("Phone (optional)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Phone"), phone);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter a name.").showAndWait();
                return null;
            }
            String phoneValue = phone.getText().isBlank() ? null : phone.getText().trim();
            return new DeliveryBoyDtos.CreateDeliveryBoyRequest(name.getText().trim(), phoneValue);
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/delivery-boys", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-delivery-boy-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editDeliveryBoyDialog(DeliveryBoyDtos.DeliveryBoyDto deliveryBoy) {
        Dialog<DeliveryBoyDtos.UpdateDeliveryBoyRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit - " + deliveryBoy.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField name = new TextField(deliveryBoy.name());
        TextField phone = new TextField(deliveryBoy.phone() == null ? "" : deliveryBoy.phone());
        CheckBox active = new CheckBox("Active");
        active.setSelected(deliveryBoy.active());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Phone"), phone);
        grid.addRow(2, new Label(""), active);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Name cannot be blank.").showAndWait();
                return null;
            }
            String phoneValue = phone.getText().isBlank() ? null : phone.getText().trim();
            return new DeliveryBoyDtos.UpdateDeliveryBoyRequest(name.getText().trim(), phoneValue, active.isSelected(), deliveryBoy.version());
        });

        dialog.showAndWait().ifPresent(request -> updateDeliveryBoy(deliveryBoy, request));
    }

    private void updateDeliveryBoy(DeliveryBoyDtos.DeliveryBoyDto deliveryBoy, DeliveryBoyDtos.UpdateDeliveryBoyRequest request) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/delivery-boys/" + deliveryBoy.id(), request);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING, "This delivery boy changed elsewhere - showing the latest version. Please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-delivery-boy-update");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
