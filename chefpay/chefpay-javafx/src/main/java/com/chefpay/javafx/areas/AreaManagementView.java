package com.chefpay.javafx.areas;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.AreaDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

/**
 * Seating-section preset CRUD (Round 8) - backs the Section/Area picker on the Table Setup screen.
 * Deliberately the smallest possible admin screen:
 * a flat row list ordered by {@code displayOrder} (the server already returns it pre-sorted -
 * this view never re-sorts), a create dialog, and an edit dialog with the same optimistic-lock
 * {@code VERSION_CONFLICT} handling every other admin screen in this app uses. Read is gated on
 * {@code TABLE_VIEW} implicitly (the server allows it), writes are gated on {@code TABLE_MANAGE} -
 * this endpoint's actual write permission, per {@code AreaController}.
 */
public class AreaManagementView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox rowsBox = new VBox(8);
    private final Label statusLabel = new Label();

    public AreaManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        rowsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Areas");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("TABLE_MANAGE")) {
            Button addArea = new Button("+ New Area");
            addArea.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addArea.setOnAction(e -> newAreaDialog());
            header.getChildren().add(header.getChildren().size() - 1, addArea);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/areas");
                List<AreaDtos.AreaDto> areas = apiClient.convertList(data, AreaDtos.AreaDto.class);
                Platform.runLater(() -> render(areas));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load areas: " + ex.getMessage()));
            }
        }, "chefpay-areas-admin-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<AreaDtos.AreaDto> areas) {
        statusLabel.setText(areas.size() + " area(s)");
        rowsBox.getChildren().clear();
        if (areas.isEmpty()) {
            rowsBox.getChildren().add(new Label("No areas yet - use \"+ New Area\" above to add one."));
            return;
        }
        for (AreaDtos.AreaDto area : areas) {
            rowsBox.getChildren().add(buildAreaRow(area));
        }
    }

    private HBox buildAreaRow(AreaDtos.AreaDto area) {
        Label name = new Label(area.name());
        name.setStyle("-fx-font-size: 14px;" + (area.active() ? "" : " -fx-text-fill: #999;"));
        name.setPrefWidth(240);

        Label order = new Label("Order: " + area.displayOrder());
        order.setPrefWidth(100);

        Label status = new Label(area.active() ? "" : "INACTIVE");
        status.setStyle("-fx-text-fill: #c0392b; -fx-font-size: 11px; -fx-font-weight: bold;");
        status.setPrefWidth(90);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, name, order, status, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 12));

        if (SessionStore.get().hasPermission("TABLE_MANAGE")) {
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editAreaDialog(area));
            row.getChildren().add(edit);
        }
        return row;
    }

    private void newAreaDialog() {
        Dialog<AreaDtos.CreateAreaRequest> dialog = new Dialog<>();
        dialog.setTitle("New Area");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("e.g. Indoor, Patio, AC Hall");
        TextField order = new TextField("0");
        order.setPromptText("Display order (lower shows first)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Display order"), order);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter an area name.").showAndWait();
                return null;
            }
            try {
                int displayOrder = order.getText().isBlank() ? 0 : Integer.parseInt(order.getText().trim());
                // branchId left null - the server resolves the single seeded branch on create.
                return new AreaDtos.CreateAreaRequest(null, name.getText().trim(), displayOrder);
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Display order must be a whole number.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/areas", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-area-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editAreaDialog(AreaDtos.AreaDto area) {
        Dialog<AreaDtos.UpdateAreaRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit - " + area.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField name = new TextField(area.name());
        TextField order = new TextField(String.valueOf(area.displayOrder()));
        CheckBox active = new CheckBox("Active");
        active.setSelected(area.active());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Display order"), order);
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
            try {
                int displayOrder = order.getText().isBlank() ? 0 : Integer.parseInt(order.getText().trim());
                return new AreaDtos.UpdateAreaRequest(name.getText().trim(), displayOrder, active.isSelected(), area.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Display order must be a whole number.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> updateArea(area, request));
    }

    private void updateArea(AreaDtos.AreaDto area, AreaDtos.UpdateAreaRequest request) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/areas/" + area.id(), request);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING, "This area changed elsewhere - showing the latest version. Please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-area-update");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
