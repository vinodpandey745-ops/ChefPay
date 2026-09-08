package com.chefpay.javafx.inventory;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.InventoryDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stock room screen - Phase 5's Inventory slice (ARCHITECTURE.md §13). Same "no client-side money
 * math, re-render straight from the server's response" discipline as {@code BillingView}: every
 * action posts to {@code /api/inventory/*} and repaints from what comes back, rather than
 * incrementing a local quantity. Receive/Adjust/Waste actions are hidden (not just disabled) unless
 * the logged-in user holds {@code INVENTORY_MANAGE} - view-only staff (e.g. Kitchen role) still get
 * {@code INVENTORY_VIEW} so they can see stock levels without being able to change them.
 */
public class InventoryView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;

    private final ListView<InventoryDtos.ItemDto> itemList = new ListView<>();
    private final VBox detailBox = new VBox(12);
    private final Label statusLabel = new Label();

    private InventoryDtos.ItemDto selectedItem;

    public InventoryView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        root.setLeft(buildItemList());
        root.setCenter(buildDetailPanel());

        renderEmptyDetail("Select an item on the left to view stock detail.");
    }

    private HBox buildHeader() {
        Label title = new Label("Inventory");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("INVENTORY_MANAGE")) {
            Button addItem = new Button("+ New Item");
            addItem.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addItem.setOnAction(e -> newItemDialog());
            header.getChildren().add(header.getChildren().size() - 1, addItem);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private VBox buildItemList() {
        itemList.setPrefWidth(320);
        itemList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(InventoryDtos.ItemDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    setGraphic(null);
                    return;
                }
                setGraphic(null);
                setText(item.name() + "\n" + item.quantityOnHand() + " " + item.unit()
                        + (item.lowStock() ? "  ⚠ LOW STOCK" : ""));
                setTextFill(item.lowStock() ? Color.web("#c0392b") : Color.BLACK);
            }
        });
        itemList.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item != null) {
                this.selectedItem = item;
                renderDetail(item);
            }
        });

        VBox box = new VBox(itemList);
        box.setPadding(new Insets(16, 0, 16, 16));
        VBox.setVgrow(itemList, Priority.ALWAYS);
        return box;
    }

    private ScrollPane buildDetailPanel() {
        detailBox.setPadding(new Insets(16, 24, 24, 24));
        ScrollPane scroll = new ScrollPane(detailBox);
        scroll.setFitToWidth(true);
        return scroll;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/inventory/items");
                List<InventoryDtos.ItemDto> items = apiClient.convertList(data, InventoryDtos.ItemDto.class);
                Platform.runLater(() -> renderItemList(items));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load inventory: " + ex.getMessage()));
            }
        }, "chefpay-inventory-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderItemList(List<InventoryDtos.ItemDto> items) {
        long lowStockCount = items.stream().filter(InventoryDtos.ItemDto::lowStock).count();
        statusLabel.setText(items.size() + " item(s)" + (lowStockCount > 0 ? "  •  " + lowStockCount + " low on stock" : ""));
        String selectedId = selectedItem == null ? null : selectedItem.id().toString();
        itemList.getItems().setAll(items);
        items.stream().filter(i -> i.id().toString().equals(selectedId)).findFirst()
                .ifPresentOrElse(i -> { selectedItem = i; itemList.getSelectionModel().select(i); renderDetail(i); },
                        () -> { selectedItem = null; });
    }

    private void renderEmptyDetail(String message) {
        detailBox.getChildren().setAll(new Label(message));
    }

    private void renderDetail(InventoryDtos.ItemDto item) {
        detailBox.getChildren().clear();

        Label header = new Label(item.name());
        header.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        detailBox.getChildren().add(header);

        detailBox.getChildren().add(row("On hand", item.quantityOnHand() + " " + item.unit()));
        detailBox.getChildren().add(row("Reorder threshold",
                item.reorderThreshold() == null ? "not set" : item.reorderThreshold() + " " + item.unit()));
        if (item.costPerUnit() != null) {
            detailBox.getChildren().add(row("Cost per unit", "₹" + item.costPerUnit()));
        }
        if (item.lowStock()) {
            Label warning = new Label("⚠ Running low - at or below reorder threshold");
            warning.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
            detailBox.getChildren().add(warning);
        }

        detailBox.getChildren().add(new Separator());

        if (SessionStore.get().hasPermission("INVENTORY_MANAGE")) {
            FlowPane actions = new FlowPane(10, 10);
            actions.getChildren().add(transactionButton("Receive Stock", "RECEIVE", "#27ae60"));
            actions.getChildren().add(transactionButton("Adjust", "ADJUST", "#2c3e50"));
            actions.getChildren().add(transactionButton("Waste", "WASTE", "#c0392b"));
            actions.getChildren().add(transactionButton("Deduct", "DEDUCT", "#c0392b"));
            Button edit = new Button("Edit Item");
            edit.setOnAction(e -> editItemDialog(item));
            actions.getChildren().add(edit);
            detailBox.getChildren().add(actions);
        }

        detailBox.getChildren().add(new Separator());
        Label historyHeader = new Label("Recent movements");
        historyHeader.setStyle("-fx-font-weight: bold;");
        detailBox.getChildren().add(historyHeader);
        loadTransactionHistory(item);
    }

    private HBox row(String label, String value) {
        Label l = new Label(label);
        Label v = new Label(value);
        v.setStyle("-fx-font-weight: bold;");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(8, l, spacer, v);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Button transactionButton(String label, String type, String color) {
        Button button = new Button(label);
        button.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold;");
        button.setOnAction(e -> transactionDialog(type, label));
        return button;
    }

    private void transactionDialog(String type, String label) {
        Dialog<InventoryDtos.RecordTransactionRequest> dialog = new Dialog<>();
        dialog.setTitle(label + " - " + selectedItem.name());
        ButtonType okType = new ButtonType(label, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        TextField quantity = new TextField();
        quantity.setPromptText("Quantity in " + selectedItem.unit());
        TextField reason = new TextField();
        reason.setPromptText("Reason (required for the audit trail)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Quantity"), quantity);
        grid.addRow(1, new Label("Reason"), reason);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != okType) {
                return null;
            }
            // The server rejects a blank reason (it's required for the audit trail) with a raw
            // Bean Validation error that isn't friendly to show as-is - catch it here instead so
            // the user gets a plain message rather than a "Field error in object..." dump.
            if (reason.getText() == null || reason.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter a reason - it's recorded on the audit trail.").showAndWait();
                return null;
            }
            try {
                BigDecimal q = new BigDecimal(quantity.getText().trim());
                return new InventoryDtos.RecordTransactionRequest(type, q, reason.getText().trim(), selectedItem.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid quantity.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/inventory/items/" + selectedItem.id() + "/transactions", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> handleError(ex));
                }
            }, "chefpay-inventory-txn");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void newItemDialog() {
        Dialog<InventoryDtos.CreateItemRequest> dialog = new Dialog<>();
        dialog.setTitle("New Inventory Item");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("e.g. Basmati Rice");
        TextField unit = new TextField();
        unit.setPromptText("e.g. kg, ltr, pcs");
        TextField opening = new TextField();
        opening.setPromptText("Opening quantity (optional, default 0)");
        TextField threshold = new TextField();
        threshold.setPromptText("Reorder threshold (optional)");
        TextField cost = new TextField();
        cost.setPromptText("Cost per unit (optional)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Unit"), unit);
        grid.addRow(2, new Label("Opening qty"), opening);
        grid.addRow(3, new Label("Reorder at"), threshold);
        grid.addRow(4, new Label("Cost/unit"), cost);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank() || unit.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Name and unit are required.").showAndWait();
                return null;
            }
            try {
                return new InventoryDtos.CreateItemRequest(name.getText().trim(), unit.getText().trim(),
                        parseOrNull(opening.getText()), parseOrNull(threshold.getText()), parseOrNull(cost.getText()));
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter valid numbers for quantity/threshold/cost.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/inventory/items", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> handleError(ex));
                }
            }, "chefpay-inventory-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editItemDialog(InventoryDtos.ItemDto item) {
        TextInputDialog dialog = new TextInputDialog(item.reorderThreshold() == null ? "" : item.reorderThreshold().toPlainString());
        dialog.setTitle("Edit Reorder Threshold");
        dialog.setHeaderText(item.name());
        dialog.setContentText("New reorder threshold (blank to leave unchanged):");
        dialog.showAndWait().ifPresent(text -> {
            if (text.isBlank()) {
                return;
            }
            BigDecimal newThreshold;
            try {
                newThreshold = new BigDecimal(text.trim());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid number.").showAndWait();
                return;
            }
            // Lightweight ad-hoc PATCH instead of a full edit dialog for this MVP: only the reorder
            // threshold is editable here; name/unit/cost/active editing can grow into a proper form later.
            Thread worker = new Thread(() -> {
                try {
                    var body = java.util.Map.of("reorderThreshold", newThreshold, "version", item.version());
                    apiClient.patch("/api/inventory/items/" + item.id(), body);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> handleError(ex));
                }
            }, "chefpay-inventory-edit");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void loadTransactionHistory(InventoryDtos.ItemDto item) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/inventory/items/" + item.id() + "/transactions");
                List<InventoryDtos.TransactionDto> transactions = apiClient.convertList(data, InventoryDtos.TransactionDto.class);
                Platform.runLater(() -> renderTransactionHistory(item, transactions));
            } catch (ApiException ex) {
                Platform.runLater(() -> detailBox.getChildren().add(new Label("Could not load history: " + ex.getMessage())));
            }
        }, "chefpay-inventory-history");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderTransactionHistory(InventoryDtos.ItemDto item, List<InventoryDtos.TransactionDto> transactions) {
        if (selectedItem == null || !selectedItem.id().equals(item.id())) {
            return; // user navigated away before this loaded
        }
        if (transactions.isEmpty()) {
            detailBox.getChildren().add(new Label("No movements recorded yet."));
            return;
        }
        for (InventoryDtos.TransactionDto txn : transactions.stream().limit(20).toList()) {
            String sign = (txn.type().equals("RECEIVE") || txn.type().equals("ADJUST")) ? "+" : "-";
            Label line = new Label(txn.createdAt() + "  " + txn.type() + "  " + sign + txn.quantity() + " " + item.unit()
                    + "  →  " + txn.resultingQuantity() + "  (" + txn.reason() + (txn.recordedByName() != null ? " • " + txn.recordedByName() : "") + ")");
            line.setWrapText(true);
            line.setStyle("-fx-font-size: 12px; -fx-text-fill: #444;");
            detailBox.getChildren().add(line);
        }
    }

    private BigDecimal parseOrNull(String text) {
        return text == null || text.isBlank() ? null : new BigDecimal(text.trim());
    }

    private void handleError(ApiException ex) {
        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
            reload();
            new Alert(Alert.AlertType.WARNING, "This item changed elsewhere - showing the latest version. Please retry.").showAndWait();
        } else {
            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
        }
    }

    public Parent view() {
        return root;
    }
}
