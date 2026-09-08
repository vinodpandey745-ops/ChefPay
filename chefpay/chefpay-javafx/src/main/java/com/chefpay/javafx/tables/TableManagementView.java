package com.chefpay.javafx.tables;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.AreaDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.client.dto.TableAdminDtos;
import com.chefpay.javafx.client.dto.TableDto;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.UUID;

/**
 * Table setup - add tables and adjust seating capacity/name/section, which {@code TableMatrixView}
 * only ever displays and never lets anyone create or edit. Backend support
 * (`POST /api/tables`, `PATCH /api/tables/{id}`) has existed since Phase 1; this was purely a
 * missing client screen, same gap as {@code MenuManagementView}. Deliberately does not expose
 * table STATUS here - that's order-driven (`OrderService#syncTableStatus`) and editing it directly
 * from this screen would fight with that, not complement it; this screen only owns the table's
 * static identity (name/capacity/section) and whether it exists at all (active).
 */
public class TableManagementView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox tableRows = new VBox(8);
    private final Label statusLabel = new Label();

    private UUID defaultFloorId;
    private UUID defaultBranchId;

    public TableManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(tableRows);
        scroll.setFitToWidth(true);
        tableRows.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Table Setup");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("TABLE_MANAGE")) {
            Button addTable = new Button("+ New Table");
            addTable.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addTable.setOnAction(e -> newTableDialog());
            header.getChildren().add(header.getChildren().size() - 1, addTable);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var restaurantData = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(restaurantData, RestaurantDtos.RestaurantDto.class);
                UUID branchId = restaurant.branches().stream().findFirst()
                        .map(RestaurantDtos.BranchDto::id)
                        .orElse(null);
                UUID floorId = restaurant.branches().stream().findFirst()
                        .flatMap(b -> b.floors().stream().findFirst())
                        .map(RestaurantDtos.FloorDto::id)
                        .orElse(null);

                var tablesData = apiClient.get("/api/tables");
                List<TableDto> tables = apiClient.convertList(tablesData, TableDto.class);
                Platform.runLater(() -> {
                    this.defaultFloorId = floorId;
                    this.defaultBranchId = branchId;
                    render(tables);
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load tables: " + ex.getMessage()));
            }
        }, "chefpay-tables-admin-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<TableDto> tables) {
        statusLabel.setText(tables.size() + " table(s)");
        tableRows.getChildren().clear();
        if (tables.isEmpty()) {
            tableRows.getChildren().add(new Label("No tables yet - use \"+ New Table\" above to set up your floor."));
            return;
        }
        Label columnHeader = new Label(String.format("%-10s %-10s %-14s %-16s", "Table", "Seats", "Section", "Status"));
        columnHeader.setStyle("-fx-font-family: monospace; -fx-font-weight: bold; -fx-text-fill: #888; -fx-font-size: 11px;");
        tableRows.getChildren().add(columnHeader);
        for (TableDto table : tables) {
            tableRows.getChildren().add(buildTableRow(table));
        }
    }

    private HBox buildTableRow(TableDto table) {
        Label name = new Label(table.name());
        name.setPrefWidth(100);
        name.setStyle(table.active() ? "" : "-fx-text-fill: #999;");
        Label seats = new Label(table.seatingCapacity() + " seats");
        seats.setPrefWidth(80);
        Label section = new Label(table.section() == null ? "-" : table.section());
        section.setPrefWidth(120);
        Label status = new Label(table.status().replace('_', ' ') + (table.active() ? "" : "  (INACTIVE)"));
        status.setPrefWidth(160);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, name, seats, section, status, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-border-color: #eee; -fx-border-width: 0 0 1 0;");

        if (SessionStore.get().hasPermission("TABLE_MANAGE")) {
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editTableDialog(table));
            row.getChildren().add(edit);
        }
        return row;
    }

    private void newTableDialog() {
        if (defaultFloorId == null) {
            new Alert(Alert.AlertType.ERROR, "No floor is configured yet for this restaurant - "
                    + "set up a branch/floor first (POST /api/restaurant/branches and .../floors), "
                    + "then retry. A freshly seeded restaurant already has one, so this usually means "
                    + "the restaurant record itself hasn't loaded - hit Refresh and try again.").showAndWait();
            return;
        }
        Dialog<TableAdminDtos.CreateTableRequest> dialog = new Dialog<>();
        dialog.setTitle("New Table");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("e.g. T07");
        TextField seats = new TextField("4");
        ComboBox<String> section = new ComboBox<>();
        section.setEditable(true);
        section.getEditor().setPromptText("e.g. Main Hall, Patio (optional)");
        loadAreaOptions(section);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Seating capacity"), seats);
        grid.addRow(2, new Label("Section"), section);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter a table name.").showAndWait();
                return null;
            }
            try {
                int capacity = Integer.parseInt(seats.getText().trim());
                if (capacity <= 0) {
                    throw new NumberFormatException();
                }
                String sectionText = section.getEditor().getText();
                return new TableAdminDtos.CreateTableRequest(defaultFloorId, name.getText().trim(), capacity,
                        sectionText == null || sectionText.isBlank() ? null : sectionText.trim(), null, null);
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a seating capacity of at least 1.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/tables", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-table-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editTableDialog(TableDto table) {
        Dialog<TableAdminDtos.UpdateTableRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit - " + table.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField name = new TextField(table.name());
        TextField seats = new TextField(String.valueOf(table.seatingCapacity()));
        ComboBox<String> section = new ComboBox<>();
        section.setEditable(true);
        section.getEditor().setText(table.section() == null ? "" : table.section());
        loadAreaOptions(section);
        CheckBox active = new CheckBox("Active (shows up on the table matrix)");
        active.setSelected(table.active());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Seating capacity"), seats);
        grid.addRow(2, new Label("Section"), section);
        grid.addRow(3, new Label(""), active);
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
                int capacity = Integer.parseInt(seats.getText().trim());
                if (capacity <= 0) {
                    throw new NumberFormatException();
                }
                String sectionText = section.getEditor().getText();
                return new TableAdminDtos.UpdateTableRequest(name.getText().trim(), capacity,
                        sectionText == null || sectionText.isBlank() ? null : sectionText.trim(), null, null, null,
                        active.isSelected(), table.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a seating capacity of at least 1.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.patch("/api/tables/" + table.id(), request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> {
                        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                            reload();
                            new Alert(Alert.AlertType.WARNING, "This table changed elsewhere - showing the latest version. Please retry.").showAndWait();
                        } else {
                            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                        }
                    });
                }
            }, "chefpay-table-update");
            worker.setDaemon(true);
            worker.start();
        });
    }

    /** Populates the Section combo with the active Areas configured for this restaurant's branch,
     * fetched once when a dialog opens - see the class javadoc: {@code section} stays a free-text
     * field on {@code RestaurantTable}, this is purely a "pick from a list" convenience layered on
     * top of it via {@code combo.setEditable(true)}. A restaurant with no Areas configured yet (or
     * an Areas fetch that fails) just leaves the combo with no options - still fully usable as
     * free text, never a hard requirement to proceed. */
    private void loadAreaOptions(ComboBox<String> combo) {
        if (defaultBranchId == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                var areasData = apiClient.get("/api/areas?branchId=" + defaultBranchId);
                List<AreaDtos.AreaDto> areas = apiClient.convertList(areasData, AreaDtos.AreaDto.class);
                List<String> names = areas.stream()
                        .filter(AreaDtos.AreaDto::active)
                        .map(AreaDtos.AreaDto::name)
                        .toList();
                Platform.runLater(() -> combo.getItems().setAll(names));
            } catch (ApiException ex) {
                // Convenience layer only - leave the combo with no pre-populated options rather
                // than blocking the dialog; the editable text field keeps working regardless.
            }
        }, "chefpay-areas-load");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
