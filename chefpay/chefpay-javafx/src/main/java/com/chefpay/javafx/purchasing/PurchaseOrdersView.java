package com.chefpay.javafx.purchasing;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.InventoryDtos;
import com.chefpay.javafx.client.dto.PurchaseOrderDtos;
import com.chefpay.javafx.client.dto.SupplierDtos;
import com.chefpay.javafx.common.ReceiptPrinter;
import com.chefpay.javafx.common.WhatsAppSender;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Purchase Order workbench - Round 12 §12-§26: full CRUD + status lifecycle, the Manager approval
 * actions (§14 - "dedicated Manager PO Approval screen: Approve/Reject/View/Print, optional
 * rejection reason" is met here as a permission-gated action set on this same detail panel rather
 * than a separately duplicated screen showing the identical PO detail a second time - see this
 * class's own section below), the offline supplier sharing workflow (§19-§20), receiving (§21-§24),
 * and the rule/AI replenishment suggestions (§21). Every action re-fetches and re-renders from the
 * server's response, same "no client-side state drifts from the server" discipline every other
 * screen in this app follows.
 */
public class PurchaseOrdersView {

    private static final List<String> STATUS_FILTERS = List.of("All", "DRAFT", "PENDING_APPROVAL", "APPROVED",
            "SENT_TO_SUPPLIER", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED", "REJECTED", "CANCELLED");

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final ListView<PurchaseOrderDtos.PurchaseOrderDto> poList = new ListView<>();
    private final VBox detailBox = new VBox(12);
    private final Label statusLabel = new Label();
    private final ChoiceBox<String> statusFilter = new ChoiceBox<>();

    private List<PurchaseOrderDtos.PurchaseOrderDto> allOrders = List.of();
    private PurchaseOrderDtos.PurchaseOrderDto selectedOrder;

    public PurchaseOrdersView(ApiClient apiClient) {
        this.apiClient = apiClient;
        root.setTop(buildHeader());
        root.setLeft(buildList());
        root.setCenter(buildDetailPanel());
        renderEmptyDetail("Select a purchase order on the left.");
    }

    private HBox buildHeader() {
        Label title = new Label("Purchase Orders");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        statusFilter.getItems().setAll(STATUS_FILTERS);
        statusFilter.getSelectionModel().selectFirst();
        statusFilter.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> applyFilter());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, new Label("Status:"), statusFilter, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("PURCHASE_ORDER_CREATE") || SessionStore.get().hasPermission("PURCHASE_ORDER_MODIFY")) {
            Button suggestions = new Button("Suggestions");
            suggestions.setOnAction(e -> replenishmentSuggestionsDialog());
            header.getChildren().add(header.getChildren().size() - 1, suggestions);

            Button create = new Button("+ New PO");
            create.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            create.setOnAction(e -> createPurchaseOrderDialog(null));
            header.getChildren().add(header.getChildren().size() - 1, create);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    private VBox buildList() {
        poList.setPrefWidth(300);
        poList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PurchaseOrderDtos.PurchaseOrderDto po, boolean empty) {
                super.updateItem(po, empty);
                if (empty || po == null) {
                    setText(null);
                } else {
                    setText(po.poNumber() + "\n" + po.supplierName() + " • " + po.status().replace('_', ' '));
                }
            }
        });
        poList.getSelectionModel().selectedItemProperty().addListener((obs, old, po) -> {
            if (po != null) {
                loadDetail(po.id());
            }
        });
        VBox box = new VBox(poList);
        box.setPadding(new Insets(16, 0, 16, 16));
        VBox.setVgrow(poList, Priority.ALWAYS);
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
                var data = apiClient.get("/api/purchasing/purchase-orders");
                List<PurchaseOrderDtos.PurchaseOrderDto> orders = apiClient.convertList(data, PurchaseOrderDtos.PurchaseOrderDto.class);
                Platform.runLater(() -> { allOrders = orders; applyFilter(); });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load purchase orders: " + ex.getMessage()));
            }
        }, "chefpay-po-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyFilter() {
        String filter = statusFilter.getValue();
        List<PurchaseOrderDtos.PurchaseOrderDto> filtered = (filter == null || "All".equals(filter))
                ? allOrders : allOrders.stream().filter(po -> po.status().equals(filter)).toList();
        statusLabel.setText(filtered.size() + " purchase order(s)");
        String selectedId = selectedOrder == null ? null : selectedOrder.id().toString();
        poList.getItems().setAll(filtered);
        filtered.stream().filter(po -> po.id().toString().equals(selectedId)).findFirst()
                .ifPresent(po -> poList.getSelectionModel().select(po));
    }

    private void loadDetail(UUID id) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/purchasing/purchase-orders/" + id);
                PurchaseOrderDtos.PurchaseOrderDto po = apiClient.convert(data, PurchaseOrderDtos.PurchaseOrderDto.class);
                Platform.runLater(() -> renderDetail(po));
            } catch (ApiException ex) {
                Platform.runLater(() -> renderEmptyDetail("Could not load purchase order: " + ex.getMessage()));
            }
        }, "chefpay-po-detail-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderEmptyDetail(String message) {
        selectedOrder = null;
        detailBox.getChildren().setAll(new Label(message));
    }

    private void renderDetail(PurchaseOrderDtos.PurchaseOrderDto po) {
        this.selectedOrder = po;
        detailBox.getChildren().clear();

        Label header = new Label(po.poNumber() + "  •  " + po.status().replace('_', ' '));
        header.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        detailBox.getChildren().add(header);
        detailBox.getChildren().add(infoRow("Branch", po.branchName()));
        detailBox.getChildren().add(infoRow("Supplier", po.supplierName()));
        detailBox.getChildren().add(infoRow("Created By", po.createdByName()));
        if (po.approvedByName() != null) {
            detailBox.getChildren().add(infoRow("Approved By", po.approvedByName() + (po.approvedAt() == null ? "" : " on " + po.approvedAt())));
        }
        if (po.status().equals("REJECTED")) {
            detailBox.getChildren().add(infoRow("Rejected By", po.rejectedByName() + (po.rejectedAt() == null ? "" : " on " + po.rejectedAt())));
            if (po.rejectionReason() != null) {
                Label reason = new Label("Reason: " + po.rejectionReason());
                reason.setWrapText(true);
                reason.setStyle("-fx-text-fill: #c0392b;");
                detailBox.getChildren().add(reason);
            }
        }
        if (po.notes() != null && !po.notes().isBlank()) {
            detailBox.getChildren().add(infoRow("Notes", po.notes()));
        }
        detailBox.getChildren().add(new Separator());

        detailBox.getChildren().add(buildItemsTable(po));
        detailBox.getChildren().add(infoRow("TOTAL", "₹" + po.totalAmount()));
        detailBox.getChildren().add(new Separator());
        detailBox.getChildren().add(buildActionRow(po));
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

    private TableView<PurchaseOrderDtos.PurchaseOrderItemDto> buildItemsTable(PurchaseOrderDtos.PurchaseOrderDto po) {
        TableView<PurchaseOrderDtos.PurchaseOrderItemDto> table = new TableView<>();
        table.setItems(javafx.collections.FXCollections.observableArrayList(po.items()));
        table.setPrefHeight(Math.min(280, 40 + po.items().size() * 32));

        TableColumn<PurchaseOrderDtos.PurchaseOrderItemDto, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().inventoryItemName()));
        nameCol.setPrefWidth(180);

        TableColumn<PurchaseOrderDtos.PurchaseOrderItemDto, String> orderedCol = new TableColumn<>("Ordered");
        orderedCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().orderedQuantity().stripTrailingZeros().toPlainString() + " " + d.getValue().unit()));

        TableColumn<PurchaseOrderDtos.PurchaseOrderItemDto, String> priceCol = new TableColumn<>("Unit Price");
        priceCol.setCellValueFactory(d -> new SimpleStringProperty("₹" + d.getValue().unitPrice()));

        TableColumn<PurchaseOrderDtos.PurchaseOrderItemDto, String> totalCol = new TableColumn<>("Line Total");
        totalCol.setCellValueFactory(d -> new SimpleStringProperty("₹" + d.getValue().lineTotal()));

        TableColumn<PurchaseOrderDtos.PurchaseOrderItemDto, String> receivedCol = new TableColumn<>("Received");
        receivedCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().receivedQuantity().stripTrailingZeros().toPlainString()));

        TableColumn<PurchaseOrderDtos.PurchaseOrderItemDto, String> remainingCol = new TableColumn<>("Remaining");
        remainingCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().remainingQuantity().stripTrailingZeros().toPlainString()));

        table.getColumns().setAll(List.of(nameCol, orderedCol, priceCol, totalCol, receivedCol, remainingCol));
        return table;
    }

    private FlowPane buildActionRow(PurchaseOrderDtos.PurchaseOrderDto po) {
        FlowPane actions = new FlowPane(10, 10);
        boolean canModify = SessionStore.get().hasPermission("PURCHASE_ORDER_MODIFY") || SessionStore.get().hasPermission("PURCHASE_ORDER_CREATE");
        boolean canApprove = SessionStore.get().hasPermission("PURCHASE_ORDER_APPROVE");
        boolean canReject = SessionStore.get().hasPermission("PURCHASE_ORDER_REJECT") || canApprove;
        boolean canReceive = SessionStore.get().hasPermission("PURCHASE_ORDER_RECEIVE");
        boolean canCancel = SessionStore.get().hasPermission("PURCHASE_ORDER_CANCEL");

        Button document = new Button("View / Print");
        document.setOnAction(e -> viewDocument(po));
        actions.getChildren().add(document);

        if ("DRAFT".equals(po.status())) {
            if (canModify) {
                Button edit = new Button("Edit Items");
                edit.setOnAction(e -> createPurchaseOrderDialog(po));
                actions.getChildren().add(edit);

                Button submit = new Button("Submit for Approval");
                submit.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
                submit.setOnAction(e -> submitForApproval(po));
                actions.getChildren().add(submit);
            }
        }
        // Round 12 §14 - the "dedicated Manager PO Approval" actions: Approve/Reject, only shown
        // to someone holding the approval permission, only while genuinely pending.
        if ("PENDING_APPROVAL".equals(po.status())) {
            if (canApprove) {
                Button approve = new Button("Approve");
                approve.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
                approve.setOnAction(e -> approve(po));
                actions.getChildren().add(approve);
            }
            if (canReject) {
                Button reject = new Button("Reject");
                reject.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold;");
                reject.setOnAction(e -> rejectDialog(po));
                actions.getChildren().add(reject);
            }
        }
        if (List.of("APPROVED", "SENT_TO_SUPPLIER", "PARTIALLY_RECEIVED").contains(po.status()) && canModify) {
            Button send = new Button("Send to Supplier");
            send.setOnAction(e -> shareDialog(po));
            actions.getChildren().add(send);
        }
        if (List.of("APPROVED", "SENT_TO_SUPPLIER", "PARTIALLY_RECEIVED").contains(po.status()) && canReceive) {
            Button receive = new Button("Receive");
            receive.setStyle("-fx-background-color: #16a085; -fx-text-fill: white; -fx-font-weight: bold;");
            receive.setOnAction(e -> receiveDialog(po));
            actions.getChildren().add(receive);
        }
        if ("RECEIVED".equals(po.status()) && (canApprove || canReceive)) {
            Button close = new Button("Close");
            close.setOnAction(e -> close(po));
            actions.getChildren().add(close);
        }
        if (canCancel) {
            boolean cancellable = List.of("DRAFT", "PENDING_APPROVAL", "APPROVED", "SENT_TO_SUPPLIER", "PARTIALLY_RECEIVED").contains(po.status());
            if (cancellable) {
                Button cancel = new Button("Cancel PO");
                cancel.setOnAction(e -> cancel(po));
                actions.getChildren().add(cancel);
            }
        }
        return actions;
    }

    // ---- actions ----

    private void submitForApproval(PurchaseOrderDtos.PurchaseOrderDto po) {
        run("/api/purchasing/purchase-orders/" + po.id() + "/submit?version=" + po.version(), null, "chefpay-po-submit");
    }

    private void approve(PurchaseOrderDtos.PurchaseOrderDto po) {
        run("/api/purchasing/purchase-orders/" + po.id() + "/approve?version=" + po.version(), null, "chefpay-po-approve");
    }

    private void rejectDialog(PurchaseOrderDtos.PurchaseOrderDto po) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Reject Purchase Order");
        dialog.setHeaderText("Reject " + po.poNumber() + "?");
        dialog.setContentText("Reason (optional):");
        dialog.showAndWait().ifPresent(reason ->
                run("/api/purchasing/purchase-orders/" + po.id() + "/reject",
                        new PurchaseOrderDtos.RejectPurchaseOrderRequest(reason.isBlank() ? null : reason, po.version()), "chefpay-po-reject"));
    }

    private void cancel(PurchaseOrderDtos.PurchaseOrderDto po) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Cancel " + po.poNumber() + "? This can't be undone.");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                run("/api/purchasing/purchase-orders/" + po.id() + "/cancel?version=" + po.version(), null, "chefpay-po-cancel"));
    }

    private void close(PurchaseOrderDtos.PurchaseOrderDto po) {
        run("/api/purchasing/purchase-orders/" + po.id() + "/close?version=" + po.version(), null, "chefpay-po-close");
    }

    private void run(String path, Object body, String threadName) {
        runWithMethod(path, body, threadName, false);
    }

    private void runPatch(String path, Object body, String threadName) {
        runWithMethod(path, body, threadName, true);
    }

    private void runWithMethod(String path, Object body, String threadName, boolean patch) {
        Thread worker = new Thread(() -> {
            try {
                if (patch) {
                    apiClient.patch(path, body);
                } else {
                    apiClient.post(path, body);
                }
                Platform.runLater(() -> { reload(); if (selectedOrder != null) loadDetail(selectedOrder.id()); });
            } catch (ApiException ex) {
                Platform.runLater(() -> handleError(ex));
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 12 §19 - Print/Email/WhatsApp. Print and WhatsApp are client-side-only (this app has
     * no real supplier API to send through) - see {@code SupplierChannel}'s javadoc for the exact
     * reasoning, identical to the existing receipt-sharing feature. Either way, this method's own
     * call to {@code /share} afterward is what actually records the audit entry. */
    private void shareDialog(PurchaseOrderDtos.PurchaseOrderDto po) {
        List<String> methods = List.of("PRINT", "EMAIL", "WHATSAPP");
        ChoiceDialog<String> methodDialog = new ChoiceDialog<>("PRINT", methods);
        methodDialog.setTitle("Send to Supplier");
        methodDialog.setHeaderText("How should " + po.poNumber() + " be sent to " + po.supplierName() + "?");
        methodDialog.showAndWait().ifPresent(method -> {
            if ("PRINT".equals(method)) {
                fetchDocumentThen(po, text -> {
                    ReceiptPrinter.show("Purchase Order - " + po.poNumber(), text);
                    run("/api/purchasing/purchase-orders/" + po.id() + "/share",
                            new PurchaseOrderDtos.ShareRequest("PRINT", null, po.version()), "chefpay-po-share-print");
                });
                return;
            }
            if ("EMAIL".equals(method)) {
                TextInputDialog emailDialog = new TextInputDialog();
                emailDialog.setTitle("Email Purchase Order");
                emailDialog.setContentText("Supplier email:");
                emailDialog.showAndWait().map(String::trim).filter(e -> !e.isBlank()).ifPresent(email ->
                        run("/api/purchasing/purchase-orders/" + po.id() + "/share",
                                new PurchaseOrderDtos.ShareRequest("EMAIL", email, po.version()), "chefpay-po-share-email"));
                return;
            }
            if ("WHATSAPP".equals(method)) {
                if (!WhatsAppSender.canSendVia()) {
                    new Alert(Alert.AlertType.WARNING, "This computer has no default browser/WhatsApp handler configured.").showAndWait();
                    return;
                }
                TextInputDialog phoneDialog = new TextInputDialog();
                phoneDialog.setTitle("WhatsApp Purchase Order");
                phoneDialog.setContentText("Supplier phone (with country code):");
                phoneDialog.showAndWait().map(String::trim).filter(p -> !p.isBlank()).ifPresent(phone ->
                        fetchDocumentThen(po, text -> {
                            boolean opened = WhatsAppSender.send(phone, text);
                            if (!opened) {
                                new Alert(Alert.AlertType.WARNING, "Could not open WhatsApp for that number.").showAndWait();
                                return;
                            }
                            run("/api/purchasing/purchase-orders/" + po.id() + "/share",
                                    new PurchaseOrderDtos.ShareRequest("WHATSAPP", phone, po.version()), "chefpay-po-share-whatsapp");
                        }));
            }
        });
    }

    private void viewDocument(PurchaseOrderDtos.PurchaseOrderDto po) {
        fetchDocumentThen(po, text -> ReceiptPrinter.show("Purchase Order - " + po.poNumber(), text));
    }

    private void fetchDocumentThen(PurchaseOrderDtos.PurchaseOrderDto po, java.util.function.Consumer<String> onLoaded) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/purchasing/purchase-orders/" + po.id() + "/document");
                String text = apiClient.convert(data, String.class);
                Platform.runLater(() -> onLoaded.accept(text));
            } catch (ApiException ex) {
                Platform.runLater(() -> handleError(ex));
            }
        }, "chefpay-po-document");
        worker.setDaemon(true);
        worker.start();
    }

    /** Round 12 §22-§24 - one dialog line per still-outstanding item; a blank/zero line is simply
     * skipped (§22 partial receiving - not every item on the PO need arrive in the same delivery). */
    private void receiveDialog(PurchaseOrderDtos.PurchaseOrderDto po) {
        List<PurchaseOrderDtos.PurchaseOrderItemDto> outstanding = po.items().stream()
                .filter(i -> i.remainingQuantity().compareTo(BigDecimal.ZERO) > 0).toList();
        if (outstanding.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Every item on this purchase order has already been fully received.").showAndWait();
            return;
        }
        Dialog<List<PurchaseOrderDtos.ReceiveLineRequest>> dialog = new Dialog<>();
        dialog.setTitle("Receive - " + po.poNumber());
        ButtonType okType = new ButtonType("Record Receiving", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        VBox rows = new VBox(10);
        record Fields(PurchaseOrderDtos.PurchaseOrderItemDto item, TextField received, TextField damaged, TextField rejected) {
        }
        List<Fields> fieldsList = new ArrayList<>();
        for (PurchaseOrderDtos.PurchaseOrderItemDto item : outstanding) {
            Label label = new Label(item.inventoryItemName() + " (remaining " + item.remainingQuantity().stripTrailingZeros().toPlainString() + " " + item.unit() + ")");
            label.setStyle("-fx-font-weight: bold;");
            TextField received = new TextField();
            received.setPromptText("Received qty");
            TextField damaged = new TextField("0");
            damaged.setPromptText("Damaged");
            TextField rejected = new TextField("0");
            rejected.setPromptText("Rejected");
            HBox row = new HBox(10, new Label("Received:"), received, new Label("Damaged:"), damaged, new Label("Rejected:"), rejected);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().addAll(label, row);
            fieldsList.add(new Fields(item, received, damaged, rejected));
        }
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(320);
        dialog.getDialogPane().setContent(scroll);

        dialog.setResultConverter(button -> {
            if (button != okType) {
                return null;
            }
            List<PurchaseOrderDtos.ReceiveLineRequest> lines = new ArrayList<>();
            try {
                for (Fields f : fieldsList) {
                    if (f.received().getText() == null || f.received().getText().isBlank()) {
                        continue;
                    }
                    BigDecimal received = new BigDecimal(f.received().getText().trim());
                    BigDecimal damaged = f.damaged().getText().isBlank() ? BigDecimal.ZERO : new BigDecimal(f.damaged().getText().trim());
                    BigDecimal rejected = f.rejected().getText().isBlank() ? BigDecimal.ZERO : new BigDecimal(f.rejected().getText().trim());
                    BigDecimal accepted = received.subtract(damaged).subtract(rejected);
                    if (accepted.compareTo(BigDecimal.ZERO) < 0) {
                        new Alert(Alert.AlertType.ERROR, "Damaged + Rejected can't exceed Received for " + f.item().inventoryItemName() + ".").showAndWait();
                        return null;
                    }
                    lines.add(new PurchaseOrderDtos.ReceiveLineRequest(f.item().id(), received, accepted, damaged, rejected, null));
                }
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter valid numbers.").showAndWait();
                return null;
            }
            if (lines.isEmpty()) {
                new Alert(Alert.AlertType.WARNING, "Enter a received quantity for at least one item.").showAndWait();
                return null;
            }
            return lines;
        });

        dialog.showAndWait().ifPresent(lines ->
                run("/api/purchasing/purchase-orders/" + po.id() + "/receive",
                        new PurchaseOrderDtos.ReceiveItemsRequest(lines, po.version()), "chefpay-po-receive"));
    }

    /** Create (existing == null) or edit-items (existing != null, must still be Draft/Pending) a
     * purchase order. Branch is always the cashier's current working branch (§23) - never offered
     * as a picker, mirroring how every other order-taking/billing screen already scopes itself. */
    private void createPurchaseOrderDialog(PurchaseOrderDtos.PurchaseOrderDto existing) {
        Thread loadSuppliers = new Thread(() -> {
            try {
                var supplierData = apiClient.get("/api/suppliers");
                List<SupplierDtos.SupplierDto> suppliers = apiClient.convertList(supplierData, SupplierDtos.SupplierDto.class);
                var itemData = apiClient.get("/api/inventory/items");
                List<InventoryDtos.ItemDto> inventoryItems = apiClient.convertList(itemData, InventoryDtos.ItemDto.class);
                Platform.runLater(() -> showPurchaseOrderForm(existing, suppliers, inventoryItems));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not load suppliers/inventory: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-po-form-load");
        loadSuppliers.setDaemon(true);
        loadSuppliers.start();
    }

    private void showPurchaseOrderForm(PurchaseOrderDtos.PurchaseOrderDto existing, List<SupplierDtos.SupplierDto> suppliers,
                                        List<InventoryDtos.ItemDto> inventoryItems) {
        if (suppliers.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Add a supplier first (Operations > Suppliers).").showAndWait();
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "New Purchase Order" : "Edit Items - " + existing.poNumber());
        ButtonType okType = new ButtonType(existing == null ? "Create" : "Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        ChoiceBox<SupplierDtos.SupplierDto> supplierChoice = new ChoiceBox<>();
        supplierChoice.getItems().setAll(suppliers);
        supplierChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(SupplierDtos.SupplierDto s) { return s == null ? "" : s.name(); }
            @Override public SupplierDtos.SupplierDto fromString(String s) { return null; }
        });
        if (existing != null) {
            suppliers.stream().filter(s -> s.id().equals(existing.supplierId())).findFirst().ifPresent(supplierChoice::setValue);
        } else {
            supplierChoice.getSelectionModel().selectFirst();
        }

        TextField notes = new TextField(existing == null ? "" : (existing.notes() == null ? "" : existing.notes()));
        notes.setPromptText("Notes (optional)");

        VBox itemRows = new VBox(8);
        record ItemRow(ChoiceBox<InventoryDtos.ItemDto> item, TextField qty, TextField price, HBox row) {
        }
        List<ItemRow> rows = new ArrayList<>();
        Runnable addRow = null;
        var addRowHolder = new Object() {
            Runnable action;
        };
        addRowHolder.action = () -> {
            ChoiceBox<InventoryDtos.ItemDto> itemChoice = new ChoiceBox<>();
            itemChoice.getItems().setAll(inventoryItems);
            itemChoice.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(InventoryDtos.ItemDto i) { return i == null ? "" : i.name() + " (" + i.unit() + ")"; }
                @Override public InventoryDtos.ItemDto fromString(String s) { return null; }
            });
            if (!inventoryItems.isEmpty()) {
                itemChoice.getSelectionModel().selectFirst();
            }
            TextField qty = new TextField();
            qty.setPromptText("Qty");
            qty.setPrefWidth(80);
            TextField price = new TextField();
            price.setPromptText("Unit Price");
            price.setPrefWidth(100);
            Button remove = new Button("✕");
            HBox row = new HBox(8, itemChoice, qty, price, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            ItemRow itemRow = new ItemRow(itemChoice, qty, price, row);
            remove.setOnAction(e -> { itemRows.getChildren().remove(row); rows.remove(itemRow); });
            rows.add(itemRow);
            itemRows.getChildren().add(row);
        };

        if (existing != null) {
            for (PurchaseOrderDtos.PurchaseOrderItemDto item : existing.items()) {
                addRowHolder.action.run();
                ItemRow last = rows.get(rows.size() - 1);
                inventoryItems.stream().filter(i -> i.id().equals(item.inventoryItemId())).findFirst().ifPresent(last.item()::setValue);
                last.qty().setText(item.orderedQuantity().stripTrailingZeros().toPlainString());
                last.price().setText(item.unitPrice().toPlainString());
            }
        } else {
            addRowHolder.action.run();
        }

        Button addItemButton = new Button("+ Add Item");
        addItemButton.setOnAction(e -> addRowHolder.action.run());

        VBox content = new VBox(12, new Label("Supplier:"), supplierChoice, new Label("Notes:"), notes,
                new Label("Items:"), itemRows, addItemButton);
        content.setPadding(new Insets(16));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(420);
        scroll.setPrefWidth(480);
        dialog.getDialogPane().setContent(scroll);

        dialog.setResultConverter(button -> null);
        dialog.getDialogPane().lookupButton(okType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            List<PurchaseOrderDtos.CreatePurchaseOrderItemRequest> items = new ArrayList<>();
            try {
                for (ItemRow row : rows) {
                    InventoryDtos.ItemDto selected = row.item().getValue();
                    if (selected == null || row.qty().getText().isBlank()) {
                        continue;
                    }
                    BigDecimal qty = new BigDecimal(row.qty().getText().trim());
                    BigDecimal price = row.price().getText().isBlank() ? BigDecimal.ZERO : new BigDecimal(row.price().getText().trim());
                    items.add(new PurchaseOrderDtos.CreatePurchaseOrderItemRequest(selected.id(), qty, price));
                }
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter valid numbers for quantity/price.").showAndWait();
                event.consume();
                return;
            }
            if (items.isEmpty()) {
                new Alert(Alert.AlertType.ERROR, "Add at least one item.").showAndWait();
                event.consume();
                return;
            }
            SupplierDtos.SupplierDto supplier = supplierChoice.getValue();
            if (supplier == null) {
                new Alert(Alert.AlertType.ERROR, "Pick a supplier.").showAndWait();
                event.consume();
                return;
            }
            String notesText = notes.getText() == null || notes.getText().isBlank() ? null : notes.getText().trim();
            if (existing == null) {
                UUID branchId = SessionStore.get().getCurrentBranchId();
                if (branchId == null) {
                    new Alert(Alert.AlertType.ERROR, "No branch is selected for this session.").showAndWait();
                    event.consume();
                    return;
                }
                run("/api/purchasing/purchase-orders",
                        new PurchaseOrderDtos.CreatePurchaseOrderRequest(branchId, supplier.id(), notesText, items), "chefpay-po-create");
            } else {
                runPatch("/api/purchasing/purchase-orders/" + existing.id(),
                        new PurchaseOrderDtos.UpdatePurchaseOrderRequest(supplier.id(), notesText, items, existing.version()), "chefpay-po-edit");
            }
        });

        dialog.showAndWait();
    }

    /** Round 12 §21 - review, don't act. Nothing here creates or sends anything on its own; the
     * "Create Draft PO" button just pre-fills the same create dialog above with the picked
     * suggestions so a manager still explicitly reviews supplier/notes/quantities before saving. */
    private void replenishmentSuggestionsDialog() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/purchasing/replenishment-suggestions");
                PurchaseOrderDtos.ReplenishmentSuggestionsResponse response =
                        apiClient.convert(data, PurchaseOrderDtos.ReplenishmentSuggestionsResponse.class);
                Platform.runLater(() -> showSuggestions(response));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
            }
        }, "chefpay-po-suggestions");
        worker.setDaemon(true);
        worker.start();
    }

    private void showSuggestions(PurchaseOrderDtos.ReplenishmentSuggestionsResponse response) {
        if (response.suggestions().isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Nothing needs reordering right now - every item is above its threshold "
                    + "or already covered by an open purchase order.").showAndWait();
            return;
        }
        VBox content = new VBox(10);
        if (response.aiNarrative() != null && !response.aiNarrative().isBlank()) {
            Label ai = new Label("AI summary: " + response.aiNarrative());
            ai.setWrapText(true);
            ai.setStyle("-fx-font-style: italic; -fx-text-fill: #444;");
            content.getChildren().add(ai);
            content.getChildren().add(new Separator());
        }
        for (PurchaseOrderDtos.ReplenishmentSuggestionDto s : response.suggestions()) {
            Label line = new Label(s.itemName() + ": " + s.quantityOnHand().stripTrailingZeros().toPlainString() + " " + s.unit()
                    + " on hand, suggest ordering " + s.suggestedQuantity().stripTrailingZeros().toPlainString() + " " + s.unit()
                    + (s.pendingOrderedQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? " (" + s.pendingOrderedQuantity().stripTrailingZeros().toPlainString() + " already on order)" : ""));
            line.setWrapText(true);
            Label reason = new Label(s.reason());
            reason.setWrapText(true);
            reason.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
            content.getChildren().addAll(line, reason);
        }
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(480, 400);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Suggested Purchase List");
        alert.setHeaderText(response.suggestions().size() + " item(s) suggested for reorder");
        alert.getDialogPane().setContent(scroll);
        alert.getButtonTypes().setAll(ButtonType.CLOSE);
        alert.showAndWait();
    }

    private void handleError(ApiException ex) {
        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
            reload();
            if (selectedOrder != null) {
                loadDetail(selectedOrder.id());
            }
            new Alert(Alert.AlertType.WARNING, "This purchase order changed elsewhere - showing the latest version. Please retry.").showAndWait();
        } else {
            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
        }
    }

    public Parent view() {
        return root;
    }
}
