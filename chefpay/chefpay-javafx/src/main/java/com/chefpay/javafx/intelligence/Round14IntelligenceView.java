package com.chefpay.javafx.intelligence;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.AiOpsDtos;
import com.chefpay.javafx.client.dto.AlertDtos;
import com.chefpay.javafx.client.dto.InventoryDtos;
import com.chefpay.javafx.client.dto.MenuDtos;
import com.chefpay.javafx.client.dto.MenuEngineeringDtos;
import com.chefpay.javafx.client.dto.PriceSuggestionDtos;
import com.chefpay.javafx.client.dto.PurchaseOrderDtos;
import com.chefpay.javafx.client.dto.RecipeDtos;
import com.chefpay.javafx.client.dto.ReportDtos;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.client.dto.RulePrecisionDtos;
import com.chefpay.javafx.client.dto.SupplierDtos;
import com.chefpay.javafx.client.dto.SupplierInvoiceDtos;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Round 14 (AI Backbone Addendum Phases 2-4) - one consolidated "Intelligence &amp; Insights" hub
 * covering every screen-facing Round 14 feature, deliberately built as a single tabbed view rather
 * than eight separate screens: these features are all "look at data, act on a suggestion" tools a
 * manager naturally flips between in one sitting, and a single entry on the Operations hub keeps
 * that hub from sprawling into a dozen near-identical small cards. Each tab is independent and
 * loads its own data lazily when first selected.
 */
public class Round14IntelligenceView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final TabPane tabPane = new TabPane();

    // ---- Recipes tab state ----
    private final ComboBox<MenuDtos.ItemDto> recipeMenuItemPicker = new ComboBox<>();
    private final TableView<RecipeDtos.RecipeLineDto> recipeLinesTable = new TableView<>();
    private final Label recipeStatusLabel = new Label();
    private final Spinner<Integer> servingsSpinner = new Spinner<>(1, 999, 1);
    private final TextArea recipeNotes = new TextArea();
    private List<InventoryDtos.ItemDto> inventoryItemsCache = List.of();
    private final List<RecipeDtos.RecipeLineInput> pendingLines = new ArrayList<>();
    private long recipeVersionInProgress = 0;

    // ---- Purchasing suggestions tab ----
    private final TableView<PurchaseOrderDtos.ReplenishmentSuggestionDto> suggestionsTable = new TableView<>();
    private final Label suggestionsStatusLabel = new Label();
    private List<RestaurantDtos.BranchDto> branchesCache = List.of();
    private List<SupplierDtos.SupplierDto> suppliersCache = List.of();

    // ---- Menu engineering tab ----
    private final TableView<MenuEngineeringDtos.MenuItemPerformanceDto> menuEngTable = new TableView<>();
    private final Label menuEngStatusLabel = new Label();

    // ---- Branch report tab ----
    private final TableView<ReportDtos.BranchTotalDto> branchReportTable = new TableView<>();
    private final Label branchReportStatusLabel = new Label();

    // ---- Price suggestions tab ----
    private final TableView<PriceSuggestionDtos.SuggestionDto> priceSuggestionsTable = new TableView<>();
    private final Label priceSuggestionsStatusLabel = new Label();

    // ---- Rule precision tab ----
    private final TableView<RulePrecisionDtos.RulePrecisionRowDto> rulePrecisionTable = new TableView<>();
    private final Label rulePrecisionStatusLabel = new Label();

    // ---- Alerts log tab ----
    private final TableView<AlertDtos.NotificationLogDto> alertsTable = new TableView<>();
    private final Label alertsStatusLabel = new Label();

    // ---- NL assistant tab ----
    private final TextField nlInstructionField = new TextField();
    private final Label nlResultLabel = new Label();
    private AiOpsDtos.InterpretedCommandDto nlPendingCommand;
    private final Button nlConfirmButton = new Button("Confirm & Apply");

    // ---- Supplier invoices tab (F2.3) ----
    private final TableView<SupplierInvoiceDtos.SupplierInvoiceDto> invoicesTable = new TableView<>();
    private final TableView<InvoiceLineRow> invoiceLinesTable = new TableView<>();
    private final Label invoicesStatusLabel = new Label();
    private final Label invoiceDetailLabel = new Label("Select an invoice above, or scan a new one.");
    private final TextArea invoiceNotesArea = new TextArea();
    private final Button confirmInvoiceButton = new Button("Confirm & Update Inventory");
    private final Button rejectInvoiceButton = new Button("Reject");
    private SupplierInvoiceDtos.SupplierInvoiceDto selectedInvoice;

    public Round14IntelligenceView(ApiClient apiClient) {
        this.apiClient = apiClient;

        Tab recipes = new Tab("Recipes", buildRecipesTab());
        Tab purchasing = new Tab("Replenishment", buildSuggestionsTab());
        Tab menuEng = new Tab("Menu Engineering", buildMenuEngineeringTab());
        Tab branchReport = new Tab("Branch Report", buildBranchReportTab());
        Tab pricing = new Tab("Dynamic Pricing", buildPriceSuggestionsTab());
        Tab rulePrecision = new Tab("Rule Precision", buildRulePrecisionTab());
        Tab alerts = new Tab("Alert Delivery Log", buildAlertsTab());
        Tab nlAssistant = new Tab("Ops Assistant", buildNlAssistantTab());
        Tab supplierInvoices = new Tab("Supplier Invoices", buildSupplierInvoicesTab());
        for (Tab t : List.of(recipes, purchasing, menuEng, branchReport, pricing, rulePrecision, alerts, nlAssistant, supplierInvoices)) {
            t.setClosable(false);
        }
        tabPane.getTabs().addAll(recipes, purchasing, menuEng, branchReport, pricing, rulePrecision, alerts, nlAssistant, supplierInvoices);

        Label title = new Label("Intelligence & Insights");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox header = new HBox(title);
        header.setPadding(new Insets(16, 24, 8, 24));
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");

        root.setTop(header);
        root.setCenter(tabPane);
    }

    public void reload() {
        loadRecipeItemPicker();
        loadSuggestions();
        loadMenuEngineering();
        loadBranchReport();
        loadPriceSuggestions();
        loadRulePrecision();
        loadAlerts();
        loadSupplierInvoices();
    }

    // =========================================================================================
    // Recipes (F2.1)
    // =========================================================================================

    private VBox buildRecipesTab() {
        recipeMenuItemPicker.setPromptText("Select a menu item");
        recipeMenuItemPicker.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(MenuDtos.ItemDto item) {
                return item == null ? "" : item.name();
            }

            @Override
            public MenuDtos.ItemDto fromString(String string) {
                return null;
            }
        });
        recipeMenuItemPicker.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item != null) {
                loadRecipeFor(item);
            }
        });

        // NOTE: cellValueFactory is wired with explicit lambdas rather than PropertyValueFactory
        // throughout this file - these DTOs are records (accessor methods like name(), not
        // JavaBean-style getName()), and PropertyValueFactory's reflection only ever looks for the
        // JavaBean naming convention, so it would silently render every cell blank instead of
        // failing loudly. Matches the same fix already applied in ReportsView.
        TableColumn<RecipeDtos.RecipeLineDto, String> ingredientCol = new TableColumn<>("Ingredient");
        ingredientCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().inventoryItemName()));
        TableColumn<RecipeDtos.RecipeLineDto, BigDecimal> qtyCol = new TableColumn<>("Qty / batch");
        qtyCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().quantityPerBatch()));
        TableColumn<RecipeDtos.RecipeLineDto, String> unitCol = new TableColumn<>("Unit");
        unitCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().unit()));
        recipeLinesTable.getColumns().setAll(List.of(ingredientCol, qtyCol, unitCol));
        recipeLinesTable.setPlaceholder(new Label("No recipe defined yet for this item."));
        recipeLinesTable.setPrefHeight(220);

        Button addLine = new Button("Add Ingredient Line");
        addLine.setOnAction(e -> addRecipeLineDialog());
        Button save = new Button("Save Recipe");
        save.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        save.setOnAction(e -> saveRecipe());

        HBox servingsBox = new HBox(8, new Label("Servings per batch:"), servingsSpinner);
        servingsBox.setAlignment(Pos.CENTER_LEFT);
        recipeNotes.setPromptText("Optional notes");
        recipeNotes.setPrefRowCount(2);

        VBox box = new VBox(12, recipeMenuItemPicker, servingsBox, recipeLinesTable,
                new HBox(8, addLine, save), recipeNotes, recipeStatusLabel);
        box.setPadding(new Insets(16));
        return box;
    }

    private void loadRecipeItemPicker() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/menu");
                List<MenuDtos.CategoryDto> categories = apiClient.convertList(data, MenuDtos.CategoryDto.class);
                List<MenuDtos.ItemDto> items = new ArrayList<>();
                categories.forEach(c -> items.addAll(c.items()));
                var invData = apiClient.get("/api/inventory/items");
                List<InventoryDtos.ItemDto> invItems = apiClient.convertList(invData, InventoryDtos.ItemDto.class);
                Platform.runLater(() -> {
                    recipeMenuItemPicker.setItems(FXCollections.observableArrayList(items));
                    inventoryItemsCache = invItems;
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> recipeStatusLabel.setText("Could not load menu/inventory: " + ex.getMessage()));
            }
        }, "chefpay-r14-recipe-picker-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadRecipeFor(MenuDtos.ItemDto item) {
        recipeStatusLabel.setText("Loading recipe...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/recipes/menu-item/" + item.id());
                RecipeDtos.RecipeDto recipe = apiClient.convert(data, RecipeDtos.RecipeDto.class);
                Platform.runLater(() -> {
                    recipeLinesTable.getItems().setAll(recipe.lines());
                    servingsSpinner.getValueFactory().setValue(recipe.servingsPerBatch());
                    recipeNotes.setText(recipe.notes() == null ? "" : recipe.notes());
                    recipeVersionInProgress = recipe.version();
                    pendingLines.clear();
                    recipe.lines().forEach(l -> pendingLines.add(new RecipeDtos.RecipeLineInput(l.inventoryItemId(), l.quantityPerBatch())));
                    recipeStatusLabel.setText("Cost per serving: " + (recipe.costPerServing() == null ? "unknown (missing ingredient cost)" : recipe.costPerServing()));
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    recipeLinesTable.getItems().clear();
                    pendingLines.clear();
                    servingsSpinner.getValueFactory().setValue(1);
                    recipeNotes.clear();
                    recipeVersionInProgress = 0;
                    recipeStatusLabel.setText("No recipe defined for this item yet - add ingredient lines and save.");
                });
            }
        }, "chefpay-r14-recipe-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void addRecipeLineDialog() {
        if (inventoryItemsCache.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Inventory items haven't loaded yet - try again in a moment.").showAndWait();
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Add Ingredient Line");
        ButtonType addType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);

        ComboBox<InventoryDtos.ItemDto> itemPicker = new ComboBox<>(FXCollections.observableArrayList(inventoryItemsCache));
        itemPicker.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(InventoryDtos.ItemDto item) {
                return item == null ? "" : item.name() + " (" + item.unit() + ")";
            }

            @Override
            public InventoryDtos.ItemDto fromString(String string) {
                return null;
            }
        });
        TextField qty = new TextField();
        qty.setPromptText("Quantity per batch");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Ingredient"), itemPicker);
        grid.addRow(1, new Label("Quantity per batch"), qty);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(b -> null);
        dialog.getDialogPane().lookupButton(addType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            InventoryDtos.ItemDto item = itemPicker.getValue();
            BigDecimal quantity;
            try {
                quantity = new BigDecimal(qty.getText().trim());
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid quantity.").showAndWait();
                event.consume();
                return;
            }
            if (item == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                new Alert(Alert.AlertType.ERROR, "Pick an ingredient and a positive quantity.").showAndWait();
                event.consume();
                return;
            }
            pendingLines.add(new RecipeDtos.RecipeLineInput(item.id(), quantity));
            recipeLinesTable.getItems().add(new RecipeDtos.RecipeLineDto(null, item.id(), item.name(), item.unit(), quantity));
        });
        dialog.showAndWait();
    }

    private void saveRecipe() {
        MenuDtos.ItemDto item = recipeMenuItemPicker.getValue();
        if (item == null) {
            new Alert(Alert.AlertType.WARNING, "Select a menu item first.").showAndWait();
            return;
        }
        if (pendingLines.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Add at least one ingredient line first.").showAndWait();
            return;
        }
        RecipeDtos.SaveRecipeRequest request = new RecipeDtos.SaveRecipeRequest(
                servingsSpinner.getValue(), recipeNotes.getText(), List.copyOf(pendingLines));
        recipeStatusLabel.setText("Saving...");
        Thread worker = new Thread(() -> {
            try {
                apiClient.put("/api/recipes/menu-item/" + item.id(), request);
                Platform.runLater(() -> loadRecipeFor(item));
            } catch (ApiException ex) {
                Platform.runLater(() -> recipeStatusLabel.setText("Save failed: " + ex.getMessage()));
            }
        }, "chefpay-r14-recipe-save");
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================================
    // Replenishment suggestions + create draft PO (F2.2 / F3.1)
    // =========================================================================================

    private VBox buildSuggestionsTab() {
        TableColumn<PurchaseOrderDtos.ReplenishmentSuggestionDto, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().itemName()));
        TableColumn<PurchaseOrderDtos.ReplenishmentSuggestionDto, BigDecimal> onHandCol = new TableColumn<>("On Hand");
        onHandCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().quantityOnHand()));
        TableColumn<PurchaseOrderDtos.ReplenishmentSuggestionDto, BigDecimal> suggestedCol = new TableColumn<>("Suggested Qty");
        suggestedCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().suggestedQuantity()));
        TableColumn<PurchaseOrderDtos.ReplenishmentSuggestionDto, String> reasonCol = new TableColumn<>("Reason");
        reasonCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().reason()));
        reasonCol.setPrefWidth(360);
        suggestionsTable.getColumns().setAll(List.of(nameCol, onHandCol, suggestedCol, reasonCol));
        suggestionsTable.setPlaceholder(new Label("No low-stock items right now."));
        suggestionsTable.setPrefHeight(320);

        CheckBox seasonalToggle = new CheckBox("Use day-of-week seasonality-aware forecast (F3.1)");
        seasonalToggle.setOnAction(e -> loadSuggestions(seasonalToggle.isSelected()));

        Button createDraftPo = new Button("Create Draft PO From Selected");
        createDraftPo.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        createDraftPo.setOnAction(e -> createDraftPoFromSelection());
        suggestionsTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> loadSuggestions(seasonalToggle.isSelected()));

        VBox box = new VBox(12, new HBox(12, seasonalToggle, refresh), suggestionsTable, createDraftPo, suggestionsStatusLabel);
        box.setPadding(new Insets(16));
        return box;
    }

    private void loadSuggestions() {
        loadSuggestions(false);
        Thread worker = new Thread(() -> {
            try {
                var restaurantData = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(restaurantData, RestaurantDtos.RestaurantDto.class);
                var supplierData = apiClient.get("/api/suppliers");
                List<SupplierDtos.SupplierDto> suppliers = apiClient.convertList(supplierData, SupplierDtos.SupplierDto.class);
                Platform.runLater(() -> {
                    branchesCache = restaurant.branches();
                    suppliersCache = suppliers;
                });
            } catch (ApiException ignored) {
                // Branch/supplier pickers just won't be prefilled for "create draft PO" - the dialog
                // still works, it simply has fewer choices to offer.
            }
        }, "chefpay-r14-suggestions-refdata");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadSuggestions(boolean seasonal) {
        suggestionsStatusLabel.setText("Loading...");
        String path = seasonal ? "/api/purchasing/replenishment-suggestions/seasonal" : "/api/purchasing/replenishment-suggestions";
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get(path);
                PurchaseOrderDtos.ReplenishmentSuggestionsResponse response = apiClient.convert(data, PurchaseOrderDtos.ReplenishmentSuggestionsResponse.class);
                Platform.runLater(() -> {
                    suggestionsTable.getItems().setAll(response.suggestions());
                    suggestionsStatusLabel.setText(response.suggestions().size() + " suggestion(s)"
                            + (response.aiNarrative() == null ? "" : " - " + response.aiNarrative()));
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> suggestionsStatusLabel.setText("Could not load suggestions: " + ex.getMessage()));
            }
        }, "chefpay-r14-suggestions-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void createDraftPoFromSelection() {
        List<PurchaseOrderDtos.ReplenishmentSuggestionDto> selected = List.copyOf(suggestionsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Select one or more suggestions first (click, then Ctrl/Cmd-click for more).").showAndWait();
            return;
        }
        if (branchesCache.isEmpty() || suppliersCache.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Branch/supplier reference data hasn't loaded yet - try again in a moment.").showAndWait();
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Create Draft PO From " + selected.size() + " Suggestion(s)");
        ButtonType createType = new ButtonType("Create Draft PO", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        ComboBox<RestaurantDtos.BranchDto> branchPicker = new ComboBox<>(FXCollections.observableArrayList(branchesCache));
        branchPicker.setConverter(nameConverter(RestaurantDtos.BranchDto::name));
        ComboBox<SupplierDtos.SupplierDto> supplierPicker = new ComboBox<>(FXCollections.observableArrayList(suppliersCache));
        supplierPicker.setConverter(nameConverter(SupplierDtos.SupplierDto::name));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Branch"), branchPicker);
        grid.addRow(1, new Label("Supplier"), supplierPicker);
        grid.addRow(2, new Label(""), new Label("Unit price defaults to 0 - edit the PO afterward with real supplier prices."));
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(b -> null);
        dialog.getDialogPane().lookupButton(createType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (branchPicker.getValue() == null || supplierPicker.getValue() == null) {
                new Alert(Alert.AlertType.ERROR, "Pick both a branch and a supplier.").showAndWait();
                event.consume();
                return;
            }
            List<PurchaseOrderDtos.CreatePurchaseOrderItemRequest> items = selected.stream()
                    .map(s -> new PurchaseOrderDtos.CreatePurchaseOrderItemRequest(s.inventoryItemId(), s.suggestedQuantity(), BigDecimal.ZERO))
                    .toList();
            PurchaseOrderDtos.CreateDraftPoFromSuggestionsRequest request = new PurchaseOrderDtos.CreateDraftPoFromSuggestionsRequest(
                    branchPicker.getValue().id(), supplierPicker.getValue().id(), "Created from replenishment suggestions", items);
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/purchasing/purchase-orders/from-suggestions", request);
                    Platform.runLater(() -> {
                        new Alert(Alert.AlertType.INFORMATION, "Draft PO created - edit prices/quantities in Purchase Orders before submitting.").showAndWait();
                        loadSuggestions(false);
                    });
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not create draft PO: " + ex.getMessage()).showAndWait());
                }
            }, "chefpay-r14-draft-po-create");
            worker.setDaemon(true);
            worker.start();
        });
        dialog.showAndWait();
    }

    // =========================================================================================
    // Menu Engineering Matrix (F2.4)
    // =========================================================================================

    private VBox buildMenuEngineeringTab() {
        TableColumn<MenuEngineeringDtos.MenuItemPerformanceDto, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().menuItemName()));
        TableColumn<MenuEngineeringDtos.MenuItemPerformanceDto, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().categoryName()));
        TableColumn<MenuEngineeringDtos.MenuItemPerformanceDto, BigDecimal> qtyCol = new TableColumn<>("Qty Sold");
        qtyCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().quantitySold()));
        TableColumn<MenuEngineeringDtos.MenuItemPerformanceDto, BigDecimal> revCol = new TableColumn<>("Revenue");
        revCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().revenue()));
        TableColumn<MenuEngineeringDtos.MenuItemPerformanceDto, BigDecimal> marginCol = new TableColumn<>("Contribution Margin");
        marginCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().contributionMargin()));
        TableColumn<MenuEngineeringDtos.MenuItemPerformanceDto, String> classCol = new TableColumn<>("Classification");
        classCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().classification()));
        menuEngTable.getColumns().setAll(List.of(nameCol, categoryCol, qtyCol, revCol, marginCol, classCol));
        menuEngTable.setPrefHeight(400);
        menuEngTable.setPlaceholder(new Label("No sales in the last 30 days."));

        Button refresh = new Button("Refresh (last 30 days)");
        refresh.setOnAction(e -> loadMenuEngineering());

        VBox box = new VBox(12, refresh, menuEngTable, menuEngStatusLabel);
        box.setPadding(new Insets(16));
        return box;
    }

    private void loadMenuEngineering() {
        menuEngStatusLabel.setText("Loading...");
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/reports/menu-engineering?from=" + from + "&to=" + to);
                MenuEngineeringDtos.MatrixResponseDto response = apiClient.convert(data, MenuEngineeringDtos.MatrixResponseDto.class);
                Platform.runLater(() -> {
                    menuEngTable.getItems().setAll(response.items());
                    menuEngStatusLabel.setText(response.classifiedItemCount() + " classified, " + response.unclassifiedItemCount()
                            + " unclassified (no recipe cost data) - avg qty " + response.averageQuantitySold()
                            + ", avg margin " + response.averageContributionMargin());
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> menuEngStatusLabel.setText("Could not load: " + ex.getMessage()));
            }
        }, "chefpay-r14-menu-eng-load");
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================================
    // Consolidated branch report (F2.5)
    // =========================================================================================

    private VBox buildBranchReportTab() {
        TableColumn<ReportDtos.BranchTotalDto, String> nameCol = new TableColumn<>("Branch");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().branchName()));
        TableColumn<ReportDtos.BranchTotalDto, BigDecimal> totalCol = new TableColumn<>("Total Sales");
        totalCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().totalSales()));
        TableColumn<ReportDtos.BranchTotalDto, Long> countCol = new TableColumn<>("Orders");
        countCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().orderCount()));
        branchReportTable.getColumns().setAll(List.of(nameCol, totalCol, countCol));
        branchReportTable.setPrefHeight(320);

        Button refresh = new Button("Refresh (last 30 days)");
        refresh.setOnAction(e -> loadBranchReport());

        VBox box = new VBox(12, refresh, branchReportTable, branchReportStatusLabel);
        box.setPadding(new Insets(16));
        return box;
    }

    private void loadBranchReport() {
        branchReportStatusLabel.setText("Loading...");
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/reports/branches?from=" + from + "&to=" + to);
                ReportDtos.ConsolidatedBranchReportDto response = apiClient.convert(data, ReportDtos.ConsolidatedBranchReportDto.class);
                Platform.runLater(() -> {
                    branchReportTable.getItems().setAll(response.branches());
                    branchReportStatusLabel.setText("Grand total: " + response.totalSales());
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> branchReportStatusLabel.setText("Could not load: " + ex.getMessage()));
            }
        }, "chefpay-r14-branch-report-load");
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================================
    // Dynamic pricing suggestions (F3.2)
    // =========================================================================================

    private VBox buildPriceSuggestionsTab() {
        TableColumn<PriceSuggestionDtos.SuggestionDto, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().menuItemName()));
        TableColumn<PriceSuggestionDtos.SuggestionDto, BigDecimal> currentCol = new TableColumn<>("Current Price");
        currentCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().currentPrice()));
        TableColumn<PriceSuggestionDtos.SuggestionDto, BigDecimal> suggestedCol = new TableColumn<>("Suggested Price");
        suggestedCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().suggestedPrice()));
        TableColumn<PriceSuggestionDtos.SuggestionDto, BigDecimal> marginCol = new TableColumn<>("Current Margin %");
        marginCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().currentMarginPercent()));
        TableColumn<PriceSuggestionDtos.SuggestionDto, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status()));
        TableColumn<PriceSuggestionDtos.SuggestionDto, String> reasonCol = new TableColumn<>("Reason");
        reasonCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().reason()));
        reasonCol.setPrefWidth(300);
        priceSuggestionsTable.getColumns().setAll(List.of(nameCol, currentCol, suggestedCol, marginCol, statusCol, reasonCol));
        priceSuggestionsTable.setPrefHeight(300);
        priceSuggestionsTable.setPlaceholder(new Label("No pricing suggestions."));

        Button scan = new Button("Scan Now");
        scan.setOnAction(e -> scanPriceSuggestions());
        Button apply = new Button("Apply Price Change");
        apply.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        apply.setOnAction(e -> decideSelectedPriceSuggestion(true));
        Button dismiss = new Button("Dismiss");
        dismiss.setOnAction(e -> decideSelectedPriceSuggestion(false));

        Label reminder = new Label("Apply immediately updates this item's live menu price and logs the change for audit - Dismiss leaves the price untouched.");
        reminder.setWrapText(true);
        reminder.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        VBox box = new VBox(12, new HBox(8, scan, apply, dismiss), priceSuggestionsTable, reminder, priceSuggestionsStatusLabel);
        box.setPadding(new Insets(16));
        return box;
    }

    private void loadPriceSuggestions() {
        priceSuggestionsStatusLabel.setText("Loading...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/pricing/suggestions");
                List<PriceSuggestionDtos.SuggestionDto> suggestions = apiClient.convertList(data, PriceSuggestionDtos.SuggestionDto.class);
                Platform.runLater(() -> {
                    priceSuggestionsTable.getItems().setAll(suggestions);
                    priceSuggestionsStatusLabel.setText(suggestions.size() + " suggestion(s)");
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> priceSuggestionsStatusLabel.setText("Could not load: " + ex.getMessage()));
            }
        }, "chefpay-r14-price-suggestions-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void scanPriceSuggestions() {
        priceSuggestionsStatusLabel.setText("Scanning...");
        Thread worker = new Thread(() -> {
            try {
                apiClient.post("/api/pricing/suggestions/scan", null);
                Platform.runLater(this::loadPriceSuggestions);
            } catch (ApiException ex) {
                Platform.runLater(() -> priceSuggestionsStatusLabel.setText("Scan failed: " + ex.getMessage()));
            }
        }, "chefpay-r14-price-scan");
        worker.setDaemon(true);
        worker.start();
    }

    private void decideSelectedPriceSuggestion(boolean apply) {
        PriceSuggestionDtos.SuggestionDto selected = priceSuggestionsTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            new Alert(Alert.AlertType.WARNING, "Select a suggestion first.").showAndWait();
            return;
        }
        if (apply) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "This will change " + selected.menuItemName()
                    + "'s live menu price from " + selected.currentPrice() + " to " + selected.suggestedPrice() + ". Continue?");
            confirm.setTitle("Apply Price Change");
            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isEmpty() || choice.get() != ButtonType.OK) {
                return;
            }
        }
        TextInputDialog noteDialog = new TextInputDialog();
        noteDialog.setTitle((apply ? "Apply" : "Dismiss") + " Price Suggestion");
        noteDialog.setHeaderText(selected.menuItemName());
        noteDialog.setContentText("Note (optional):");
        Optional<String> note = noteDialog.showAndWait();
        if (note.isEmpty()) {
            return;
        }
        String path = "/api/pricing/suggestions/" + selected.id() + (apply ? "/apply" : "/dismiss");
        Thread worker = new Thread(() -> {
            try {
                apiClient.post(path, new PriceSuggestionDtos.DecideRequest(note.get()));
                Platform.runLater(this::loadPriceSuggestions);
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
            }
        }, "chefpay-r14-price-decide");
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================================
    // Rule precision / manager feedback (F3.4)
    // =========================================================================================

    private VBox buildRulePrecisionTab() {
        TableColumn<RulePrecisionDtos.RulePrecisionRowDto, String> nameCol = new TableColumn<>("Rule");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().ruleName()));
        TableColumn<RulePrecisionDtos.RulePrecisionRowDto, Long> totalCol = new TableColumn<>("Total");
        totalCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().totalAnomalies()));
        TableColumn<RulePrecisionDtos.RulePrecisionRowDto, Long> theftCol = new TableColumn<>("Confirmed Theft");
        theftCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().confirmedTheft()));
        TableColumn<RulePrecisionDtos.RulePrecisionRowDto, Long> fpCol = new TableColumn<>("False Positive");
        fpCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().falsePositive()));
        TableColumn<RulePrecisionDtos.RulePrecisionRowDto, Long> unresolvedCol = new TableColumn<>("Unresolved");
        unresolvedCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().unresolved()));
        TableColumn<RulePrecisionDtos.RulePrecisionRowDto, Double> fpRateCol = new TableColumn<>("False Positive %");
        fpRateCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().falsePositiveRatePercent()));
        rulePrecisionTable.getColumns().setAll(List.of(nameCol, totalCol, theftCol, fpCol, unresolvedCol, fpRateCol));
        rulePrecisionTable.setPrefHeight(320);
        rulePrecisionTable.setPlaceholder(new Label("No anomalies in the last 30 days."));

        Button refresh = new Button("Refresh (last 30 days)");
        refresh.setOnAction(e -> loadRulePrecision());

        VBox box = new VBox(12, refresh, rulePrecisionTable, rulePrecisionStatusLabel);
        box.setPadding(new Insets(16));
        return box;
    }

    private void loadRulePrecision() {
        rulePrecisionStatusLabel.setText("Loading...");
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/fraud/rule-precision?from=" + from + "&to=" + to);
                RulePrecisionDtos.RulePrecisionReportDto response = apiClient.convert(data, RulePrecisionDtos.RulePrecisionReportDto.class);
                Platform.runLater(() -> {
                    rulePrecisionTable.getItems().setAll(response.rows());
                    rulePrecisionStatusLabel.setText(response.rows().size() + " rule(s) with activity");
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> rulePrecisionStatusLabel.setText("Could not load: " + ex.getMessage()));
            }
        }, "chefpay-r14-rule-precision-load");
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================================
    // Alert delivery log (F4.2)
    // =========================================================================================

    private VBox buildAlertsTab() {
        TableColumn<AlertDtos.NotificationLogDto, String> channelCol = new TableColumn<>("Channel");
        channelCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().channel()));
        TableColumn<AlertDtos.NotificationLogDto, String> subjectCol = new TableColumn<>("Subject");
        subjectCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().subject()));
        subjectCol.setPrefWidth(280);
        TableColumn<AlertDtos.NotificationLogDto, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status()));
        TableColumn<AlertDtos.NotificationLogDto, Boolean> escalationCol = new TableColumn<>("Escalation");
        escalationCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().escalation()));
        TableColumn<AlertDtos.NotificationLogDto, String> attemptedCol = new TableColumn<>("When");
        attemptedCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().attemptedAt())));
        alertsTable.getColumns().setAll(List.of(channelCol, subjectCol, statusCol, escalationCol, attemptedCol));
        alertsTable.setPrefHeight(360);
        alertsTable.setPlaceholder(new Label("No alerts dispatched yet."));

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> loadAlerts());

        VBox box = new VBox(12, refresh, alertsTable, alertsStatusLabel);
        box.setPadding(new Insets(16));
        return box;
    }

    private void loadAlerts() {
        alertsStatusLabel.setText("Loading...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/alerts/logs");
                List<AlertDtos.NotificationLogDto> logs = apiClient.convertList(data, AlertDtos.NotificationLogDto.class);
                Platform.runLater(() -> {
                    alertsTable.getItems().setAll(logs);
                    alertsStatusLabel.setText(logs.size() + " log entries");
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> alertsStatusLabel.setText("Could not load: " + ex.getMessage()));
            }
        }, "chefpay-r14-alerts-load");
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================================
    // NL Ops Assistant (F4.1)
    // =========================================================================================

    private VBox buildNlAssistantTab() {
        nlInstructionField.setPromptText("e.g. \"86 the Chicken Biryani\" or \"bring back Paneer Tikka\"");
        Button interpret = new Button("Interpret");
        interpret.setOnAction(e -> interpretNlCommand());
        nlConfirmButton.setDisable(true);
        nlConfirmButton.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        nlConfirmButton.setOnAction(e -> confirmNlCommand());

        Label note = new Label("Only one action is supported today: marking a menu item available/unavailable. "
                + "Nothing is applied until you review the interpreted command and click Confirm.");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        VBox box = new VBox(12, note, new HBox(8, nlInstructionField, interpret), nlResultLabel, nlConfirmButton);
        box.setPadding(new Insets(16));
        return box;
    }

    private void interpretNlCommand() {
        String instruction = nlInstructionField.getText();
        if (instruction == null || instruction.isBlank()) {
            return;
        }
        nlResultLabel.setText("Interpreting...");
        nlConfirmButton.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/ai/ops/command/interpret", new AiOpsDtos.InterpretCommandRequest(instruction));
                AiOpsDtos.InterpretedCommandDto interpreted = apiClient.convert(data, AiOpsDtos.InterpretedCommandDto.class);
                Platform.runLater(() -> {
                    nlPendingCommand = interpreted;
                    nlResultLabel.setText(interpreted.summary());
                    nlConfirmButton.setDisable(!interpreted.executable());
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> nlResultLabel.setText("Could not interpret: " + ex.getMessage()));
            }
        }, "chefpay-r14-nl-interpret");
        worker.setDaemon(true);
        worker.start();
    }

    private void confirmNlCommand() {
        if (nlPendingCommand == null || !nlPendingCommand.executable()) {
            return;
        }
        AiOpsDtos.ExecuteToggleAvailabilityRequest request = new AiOpsDtos.ExecuteToggleAvailabilityRequest(
                nlPendingCommand.menuItemId(), nlPendingCommand.proposedAvailable(), 0L);
        // Note: expectedVersion 0 relies on the server's optimistic-lock check; a genuine version
        // mismatch simply surfaces as a clear error below, prompting a re-interpret (which re-reads
        // current state) rather than silently overwriting a concurrent edit.
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/ai/ops/command/execute-toggle-availability", request);
                AiOpsDtos.ExecutedCommandDto result = apiClient.convert(data, AiOpsDtos.ExecutedCommandDto.class);
                Platform.runLater(() -> {
                    nlResultLabel.setText(result.message());
                    nlConfirmButton.setDisable(true);
                    nlPendingCommand = null;
                    nlInstructionField.clear();
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> nlResultLabel.setText("Could not apply: " + ex.getMessage()));
            }
        }, "chefpay-r14-nl-confirm");
        worker.setDaemon(true);
        worker.start();
    }

    // =========================================================================================
    // Supplier invoices - OCR-assisted intake (F2.3)
    // =========================================================================================

    private VBox buildSupplierInvoicesTab() {
        TableColumn<SupplierInvoiceDtos.SupplierInvoiceDto, String> createdCol = new TableColumn<>("Received");
        createdCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().createdAt())));
        TableColumn<SupplierInvoiceDtos.SupplierInvoiceDto, String> supplierCol = new TableColumn<>("Supplier");
        supplierCol.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().supplierName() == null ? "(none picked)" : data.getValue().supplierName()));
        TableColumn<SupplierInvoiceDtos.SupplierInvoiceDto, String> methodCol = new TableColumn<>("Extraction");
        methodCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().extractionMethod()));
        TableColumn<SupplierInvoiceDtos.SupplierInvoiceDto, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status()));
        TableColumn<SupplierInvoiceDtos.SupplierInvoiceDto, Number> lineCountCol = new TableColumn<>("Lines");
        lineCountCol.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue().lines().size()));
        invoicesTable.getColumns().setAll(List.of(createdCol, supplierCol, methodCol, statusCol, lineCountCol));
        invoicesTable.setPlaceholder(new Label("No supplier invoices scanned yet."));
        invoicesTable.setPrefHeight(220);
        invoicesTable.getSelectionModel().selectedItemProperty().addListener((obs, old, invoice) -> selectInvoice(invoice));

        Button scan = new Button("Scan New Invoice Photo...");
        scan.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
        scan.setOnAction(e -> chooseAndScanInvoice(scan.getScene() == null ? null : scan.getScene().getWindow()));
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> loadSupplierInvoices());

        Label help = new Label("Photograph or scan a paper supplier invoice. This app tries offline OCR "
                + "(Tesseract) first, with an optional AI-vision fallback if enabled in Settings, and always "
                + "falls back to fully manual entry if neither is available - review and correct every line "
                + "below before confirming, since nothing touches your inventory or landed cost until you do.");
        help.setWrapText(true);
        help.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        // ---- Line-review table for the selected invoice ----
        TableColumn<InvoiceLineRow, String> descCol = new TableColumn<>("Description (as read)");
        descCol.setCellValueFactory(data -> data.getValue().description);
        descCol.setPrefWidth(220);
        TableColumn<InvoiceLineRow, InventoryDtos.ItemDto> itemCol = new TableColumn<>("Match to Inventory Item");
        itemCol.setCellValueFactory(data -> data.getValue().inventoryItem);
        itemCol.setPrefWidth(220);
        itemCol.setCellFactory(ComboBoxTableCell.forTableColumn(nameConverter(i -> i.name() + " (" + i.unit() + ")"),
                FXCollections.observableArrayList()));
        itemCol.setOnEditCommit(evt -> evt.getRowValue().inventoryItem.set(evt.getNewValue()));
        TableColumn<InvoiceLineRow, BigDecimal> lineQtyCol = new TableColumn<>("Quantity");
        lineQtyCol.setCellValueFactory(data -> data.getValue().quantity);
        lineQtyCol.setCellFactory(TextFieldTableCell.forTableColumn(bigDecimalConverter()));
        lineQtyCol.setOnEditCommit(evt -> evt.getRowValue().quantity.set(evt.getNewValue()));
        TableColumn<InvoiceLineRow, BigDecimal> lineCostCol = new TableColumn<>("Unit Cost");
        lineCostCol.setCellValueFactory(data -> data.getValue().unitCost);
        lineCostCol.setCellFactory(TextFieldTableCell.forTableColumn(bigDecimalConverter()));
        lineCostCol.setOnEditCommit(evt -> evt.getRowValue().unitCost.set(evt.getNewValue()));
        invoiceLinesTable.getColumns().setAll(List.of(descCol, itemCol, lineQtyCol, lineCostCol));
        invoiceLinesTable.setEditable(true);
        invoiceLinesTable.setPlaceholder(new Label("Nothing selected."));
        invoiceLinesTable.setPrefHeight(240);

        Label editHelp = new Label("Double-click a cell to edit. Pick the matching inventory item and confirm "
                + "quantity/unit cost - a line left unmatched (or with no quantity) is skipped and won't affect stock.");
        editHelp.setWrapText(true);
        editHelp.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        invoiceNotesArea.setPromptText("Notes (optional)");
        invoiceNotesArea.setPrefRowCount(2);

        confirmInvoiceButton.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");
        confirmInvoiceButton.setOnAction(e -> confirmSelectedInvoice());
        confirmInvoiceButton.setDisable(true);
        rejectInvoiceButton.setOnAction(e -> rejectSelectedInvoice());
        rejectInvoiceButton.setDisable(true);

        VBox box = new VBox(12, help, new HBox(8, scan, refresh), invoicesTable, invoicesStatusLabel,
                new Separator(), invoiceDetailLabel, invoiceLinesTable, editHelp, invoiceNotesArea,
                new HBox(8, confirmInvoiceButton, rejectInvoiceButton));
        box.setPadding(new Insets(16));
        return box;
    }

    private void loadSupplierInvoices() {
        invoicesStatusLabel.setText("Loading...");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/inventory/invoices");
                List<SupplierInvoiceDtos.SupplierInvoiceDto> invoices = apiClient.convertList(data, SupplierInvoiceDtos.SupplierInvoiceDto.class);
                Platform.runLater(() -> {
                    invoicesTable.getItems().setAll(invoices);
                    invoicesStatusLabel.setText(invoices.size() + " invoice(s)");
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> invoicesStatusLabel.setText("Could not load: " + ex.getMessage()));
            }
        }, "chefpay-r14-invoices-load");
        worker.setDaemon(true);
        worker.start();
    }

    @SuppressWarnings("unchecked")
    private void selectInvoice(SupplierInvoiceDtos.SupplierInvoiceDto invoice) {
        selectedInvoice = invoice;
        invoiceLinesTable.getItems().clear();
        invoiceNotesArea.clear();
        if (invoice == null) {
            invoiceDetailLabel.setText("Select an invoice above, or scan a new one.");
            confirmInvoiceButton.setDisable(true);
            rejectInvoiceButton.setDisable(true);
            return;
        }
        boolean pending = "PENDING_REVIEW".equals(invoice.status());
        invoiceDetailLabel.setText(invoice.supplierName() == null ? "(no supplier picked)" : invoice.supplierName()
                + " - " + invoice.status() + " - extracted via " + invoice.extractionMethod()
                + (pending ? "" : " (already decided - read-only)"));
        confirmInvoiceButton.setDisable(!pending);
        rejectInvoiceButton.setDisable(!pending);
        invoiceLinesTable.setEditable(pending);

        // Refresh the inventory-item choices available to the match ComboBox column each time an
        // invoice is opened, so newly-added inventory items are pickable without restarting the app.
        TableColumn<InvoiceLineRow, InventoryDtos.ItemDto> itemCol =
                (TableColumn<InvoiceLineRow, InventoryDtos.ItemDto>) invoiceLinesTable.getColumns().get(1);
        itemCol.setCellFactory(ComboBoxTableCell.forTableColumn(nameConverter(i -> i.name() + " (" + i.unit() + ")"),
                FXCollections.observableArrayList(inventoryItemsCache)));

        List<InvoiceLineRow> rows = new ArrayList<>();
        for (SupplierInvoiceDtos.SupplierInvoiceLineDto line : invoice.lines()) {
            InventoryDtos.ItemDto matched = line.inventoryItemId() == null ? null : inventoryItemsCache.stream()
                    .filter(i -> i.id().equals(line.inventoryItemId())).findFirst().orElse(null);
            rows.add(new InvoiceLineRow(line.description(), matched, line.quantity(), line.unitCost()));
        }
        invoiceLinesTable.getItems().setAll(rows);
    }

    private void chooseAndScanInvoice(javafx.stage.Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a supplier invoice photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        SupplierDtos.SupplierDto finalSupplier = suppliersCache.isEmpty() ? null : pickSupplierForScan();
        invoicesStatusLabel.setText("Reading invoice with OCR - this can take a moment...");
        Thread worker = new Thread(() -> {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String lower = file.getName().toLowerCase();
                String mimeType = lower.endsWith(".png") ? "image/png" : "image/jpeg";
                var data = apiClient.post("/api/inventory/invoices/scan", new SupplierInvoiceDtos.ScanRequest(
                        base64, mimeType, finalSupplier == null ? null : finalSupplier.id()));
                SupplierInvoiceDtos.SupplierInvoiceDto scanned = apiClient.convert(data, SupplierInvoiceDtos.SupplierInvoiceDto.class);
                Platform.runLater(() -> {
                    loadSupplierInvoices();
                    invoicesStatusLabel.setText("Scanned via " + scanned.extractionMethod() + " - "
                            + scanned.lines().size() + " line(s) drafted. Review below before confirming.");
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> invoicesStatusLabel.setText("Scan failed: " + ex.getMessage()));
            } catch (Exception ex) {
                Platform.runLater(() -> invoicesStatusLabel.setText("Could not read that photo: " + ex.getMessage()));
            }
        }, "chefpay-r14-invoice-scan");
        worker.setDaemon(true);
        worker.start();
    }

    /** Small optional-supplier picker shown before scanning - a plain custom {@link Dialog} with a
     * {@link ComboBox} (matching this file's own {@code createDraftPoFromSelection} idiom), rather
     * than the built-in {@link ChoiceDialog}, since {@code ChoiceDialog} has no public way to attach
     * a custom {@link javafx.util.StringConverter} and would otherwise render each supplier via its
     * raw record {@code toString()}. */
    private SupplierDtos.SupplierDto pickSupplierForScan() {
        Dialog<SupplierDtos.SupplierDto> dialog = new Dialog<>();
        dialog.setTitle("Which supplier is this invoice from?");
        ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        ComboBox<SupplierDtos.SupplierDto> supplierPicker = new ComboBox<>(FXCollections.observableArrayList(suppliersCache));
        supplierPicker.setConverter(nameConverter(SupplierDtos.SupplierDto::name));
        supplierPicker.setPromptText("(optional - leave blank to skip)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Supplier"), supplierPicker);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(b -> b == okType ? supplierPicker.getValue() : null);
        return dialog.showAndWait().orElse(null);
    }

    private void confirmSelectedInvoice() {
        if (selectedInvoice == null) {
            return;
        }
        List<SupplierInvoiceDtos.ConfirmLineRequest> lines = invoiceLinesTable.getItems().stream()
                .map(row -> new SupplierInvoiceDtos.ConfirmLineRequest(
                        row.inventoryItem.get() == null ? null : row.inventoryItem.get().id(),
                        row.description.get(), row.quantity.get(), row.unitCost.get()))
                .toList();
        long matchedCount = lines.stream().filter(l -> l.inventoryItemId() != null && l.quantity() != null
                && l.quantity().compareTo(BigDecimal.ZERO) > 0).count();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, matchedCount + " of " + lines.size()
                + " line(s) are matched to an inventory item with a positive quantity and will RECEIVE stock and "
                + "update landed cost. Unmatched lines are skipped. Continue?");
        confirm.setTitle("Confirm Supplier Invoice");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        SupplierInvoiceDtos.SupplierInvoiceDto invoice = selectedInvoice;
        SupplierInvoiceDtos.ConfirmInvoiceRequest request = new SupplierInvoiceDtos.ConfirmInvoiceRequest(
                lines, invoiceNotesArea.getText(), invoice.version());
        invoicesStatusLabel.setText("Confirming and updating inventory...");
        Thread worker = new Thread(() -> {
            try {
                apiClient.post("/api/inventory/invoices/" + invoice.id() + "/confirm", request);
                Platform.runLater(() -> {
                    invoicesStatusLabel.setText("Confirmed - inventory stock and cost updated.");
                    loadSupplierInvoices();
                    loadRecipeItemPicker();
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not confirm: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-r14-invoice-confirm");
        worker.setDaemon(true);
        worker.start();
    }

    private void rejectSelectedInvoice() {
        if (selectedInvoice == null) {
            return;
        }
        SupplierInvoiceDtos.SupplierInvoiceDto invoice = selectedInvoice;
        SupplierInvoiceDtos.RejectInvoiceRequest request = new SupplierInvoiceDtos.RejectInvoiceRequest(
                invoiceNotesArea.getText(), invoice.version());
        Thread worker = new Thread(() -> {
            try {
                apiClient.post("/api/inventory/invoices/" + invoice.id() + "/reject", request);
                Platform.runLater(() -> {
                    invoicesStatusLabel.setText("Invoice rejected - no inventory changes were made.");
                    loadSupplierInvoices();
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not reject: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-r14-invoice-reject");
        worker.setDaemon(true);
        worker.start();
    }

    /** Mutable editable row backing {@link #invoiceLinesTable} - the invoice line DTOs themselves
     * are immutable records, so this wraps each one in JavaFX properties the table's editable
     * ComboBox/TextField cells can bind to and write back into on commit. */
    private static final class InvoiceLineRow {
        final SimpleStringProperty description;
        final SimpleObjectProperty<InventoryDtos.ItemDto> inventoryItem;
        final SimpleObjectProperty<BigDecimal> quantity;
        final SimpleObjectProperty<BigDecimal> unitCost;

        InvoiceLineRow(String description, InventoryDtos.ItemDto inventoryItem, BigDecimal quantity, BigDecimal unitCost) {
            this.description = new SimpleStringProperty(description == null ? "" : description);
            this.inventoryItem = new SimpleObjectProperty<>(inventoryItem);
            this.quantity = new SimpleObjectProperty<>(quantity);
            this.unitCost = new SimpleObjectProperty<>(unitCost);
        }
    }

    private javafx.util.StringConverter<BigDecimal> bigDecimalConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(BigDecimal value) {
                return value == null ? "" : value.toPlainString();
            }

            @Override
            public BigDecimal fromString(String string) {
                if (string == null || string.isBlank()) {
                    return null;
                }
                try {
                    return new BigDecimal(string.trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        };
    }

    // ---- helpers ----

    private <T> javafx.util.StringConverter<T> nameConverter(java.util.function.Function<T, String> nameFn) {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(T object) {
                return object == null ? "" : nameFn.apply(object);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }

    public Parent view() {
        return root;
    }
}
