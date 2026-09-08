package com.chefpay.javafx.menu;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.AiDtos;
import com.chefpay.javafx.client.dto.MenuDtos;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Menu catalog management - what {@code OrderTakingView}'s menu tiles actually come from. Every
 * ChefPay drop up to now has only ever seeded three demo items via {@code DataSeeder}; this screen
 * is what lets a real restaurant add its own categories and items without a developer editing that
 * seed and rebuilding. Backend support (`POST /api/menu/categories`, `POST /api/menu/items`,
 * `PATCH /api/menu/items/{id}`) has existed since Phase 1 - this was purely a missing client screen.
 * Same "re-render straight from the server's response" discipline as every other screen here.
 */
public class MenuManagementView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox categoriesBox = new VBox(20);
    private final Label statusLabel = new Label();
    private final ScrollPane scroll = new ScrollPane();

    // "Item Listing" flat table-view mode (Round 8) - added alongside the original "By Category"
    // mode without touching its rendering at all. lastCategories caches the most recently loaded
    // server response so switching modes never triggers a re-fetch; only render(...) (from reload())
    // ever repopulates it.
    private enum ViewMode { BY_CATEGORY, ITEM_LISTING }
    private ViewMode viewMode = ViewMode.BY_CATEGORY;
    private List<MenuDtos.CategoryDto> lastCategories = List.of();
    private final ToggleButton byCategoryToggle = new ToggleButton("By Category");
    private final ToggleButton itemListingToggle = new ToggleButton("Item Listing");
    private final TextField itemSearchField = new TextField();
    private final VBox itemListingRows = new VBox(4);
    private final VBox itemListingBox = new VBox(12);

    // Round 9: 3-way food-type classification (drives the veg/egg/non-veg indicator strip on
    // OrderTakingView's menu tiles) - display strings shown in the ChoiceBox map to the FoodType
    // enum names ("VEG"/"EGG"/"NON_VEG") the server expects.
    private static final List<String> FOOD_TYPE_DISPLAY = List.of("Veg", "Egg", "Non-Veg");

    private static String foodTypeCode(String display) {
        return switch (display) {
            case "Egg" -> "EGG";
            case "Non-Veg" -> "NON_VEG";
            default -> "VEG";
        };
    }

    private static String foodTypeDisplay(String code) {
        if ("EGG".equals(code)) {
            return "Egg";
        }
        if ("NON_VEG".equals(code)) {
            return "Non-Veg";
        }
        return "Veg";
    }

    public MenuManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;

        itemSearchField.setPromptText("Search items by name...");
        itemSearchField.setMaxWidth(320);
        itemSearchField.textProperty().addListener((obs, old, text) -> renderItemListing());
        itemListingBox.setPadding(new Insets(16, 24, 24, 24));
        itemListingBox.getChildren().addAll(itemSearchField, buildItemListingHeaderRow(), itemListingRows);

        root.setTop(buildHeader());
        scroll.setFitToWidth(true);
        categoriesBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
        applyViewMode();
    }

    private HBox buildHeader() {
        Label title = new Label("Menu");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        ToggleGroup modeGroup = new ToggleGroup();
        byCategoryToggle.setToggleGroup(modeGroup);
        itemListingToggle.setToggleGroup(modeGroup);
        byCategoryToggle.setUserData(ViewMode.BY_CATEGORY);
        itemListingToggle.setUserData(ViewMode.ITEM_LISTING);
        byCategoryToggle.setStyle("-fx-font-size: 12px;");
        itemListingToggle.setStyle("-fx-font-size: 12px;");
        byCategoryToggle.selectedProperty().addListener((obs, o, sel) ->
                byCategoryToggle.setStyle(sel ? "-fx-font-size: 12px; -fx-font-weight: bold;" : "-fx-font-size: 12px;"));
        itemListingToggle.selectedProperty().addListener((obs, o, sel) ->
                itemListingToggle.setStyle(sel ? "-fx-font-size: 12px; -fx-font-weight: bold;" : "-fx-font-size: 12px;"));
        modeGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                if (old != null) {
                    old.setSelected(true);
                }
                return;
            }
            viewMode = (ViewMode) now.getUserData();
            applyViewMode();
        });
        byCategoryToggle.setSelected(true);
        HBox modeBox = new HBox(0, byCategoryToggle, itemListingToggle);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, modeBox, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("MENU_MANAGE")) {
            Button addCategory = new Button("+ New Category");
            addCategory.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addCategory.setOnAction(e -> newCategoryDialog());
            header.getChildren().add(header.getChildren().size() - 1, addCategory);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    /** Swaps the ScrollPane's content between the two modes - no network call, no rebuild of the other mode's box. */
    private void applyViewMode() {
        scroll.setContent(viewMode == ViewMode.BY_CATEGORY ? categoriesBox : itemListingBox);
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/menu");
                List<MenuDtos.CategoryDto> categories = apiClient.convertList(data, MenuDtos.CategoryDto.class);
                Platform.runLater(() -> render(categories));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load menu: " + ex.getMessage()));
            }
        }, "chefpay-menu-admin-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<MenuDtos.CategoryDto> categories) {
        this.lastCategories = categories;
        int itemCount = categories.stream().mapToInt(c -> c.items().size()).sum();
        statusLabel.setText(categories.size() + " categor" + (categories.size() == 1 ? "y" : "ies")
                + "  •  " + itemCount + " item(s)");

        categoriesBox.getChildren().clear();
        if (categories.isEmpty()) {
            categoriesBox.getChildren().add(new Label("No categories yet - use \"+ New Category\" above to start your menu."));
        } else {
            for (MenuDtos.CategoryDto category : categories) {
                categoriesBox.getChildren().add(buildCategorySection(category));
            }
        }
        renderItemListing();
    }

    /** Flattens {@code lastCategories} into one row per item, filtered by the search field - client-side
     * only, re-run on every keystroke and every reload. Never re-fetches from the server. */
    private void renderItemListing() {
        itemListingRows.getChildren().clear();
        String query = itemSearchField.getText() == null ? "" : itemSearchField.getText().trim().toLowerCase();
        int shown = 0;
        for (MenuDtos.CategoryDto category : lastCategories) {
            for (MenuDtos.ItemDto item : category.items()) {
                if (!query.isEmpty() && !item.name().toLowerCase().contains(query)) {
                    continue;
                }
                itemListingRows.getChildren().add(buildItemListingRow(item, category));
                shown++;
            }
        }
        if (shown == 0) {
            itemListingRows.getChildren().add(new Label(lastCategories.isEmpty()
                    ? "No categories yet - use \"+ New Category\" above to start your menu."
                    : "No items match your search."));
        }
    }

    private HBox buildItemListingHeaderRow() {
        HBox row = new HBox(12, columnHeaderLabel("Name", 220), columnHeaderLabel("Category", 140),
                columnHeaderLabel("Price", 90), columnHeaderLabel("Half Price", 90),
                columnHeaderLabel("Veg", 60), columnHeaderLabel("Status", 90));
        row.setPadding(new Insets(4, 0, 6, 4));
        row.setStyle("-fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return row;
    }

    private Label columnHeaderLabel(String text, double width) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-font-family: 'Consolas', 'Monaco', monospace; -fx-text-fill: #555;");
        label.setPrefWidth(width);
        return label;
    }

    /** Same per-item action buttons and the same {@link #updateItem} / {@link #editItemDialog} calls as
     * {@link #buildItemRow} - just a flat, category-labelled layout instead of a grouped section. */
    private HBox buildItemListingRow(MenuDtos.ItemDto item, MenuDtos.CategoryDto category) {
        Label name = new Label(item.name() + (item.directSale() ? "  [Direct Sale]" : ""));
        name.setStyle("-fx-font-size: 13px;" + (item.available() && item.active() ? "" : " -fx-text-fill: #999;"));
        name.setPrefWidth(220);

        Label categoryLabel = new Label(category.name());
        categoryLabel.setStyle("-fx-font-size: 13px;");
        categoryLabel.setPrefWidth(140);

        Label price = new Label("₹" + item.price());
        price.setPrefWidth(90);

        Label halfPrice = new Label(item.halfPrice() != null ? "₹" + item.halfPrice() : "-");
        halfPrice.setPrefWidth(90);

        Label veg = new Label(foodTypeDisplay(item.foodType()));
        veg.setPrefWidth(60);

        Label status = new Label(!item.active() ? "INACTIVE" : (item.available() ? "Available" : "UNAVAILABLE"));
        status.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;"
                + (!item.active() || !item.available() ? " -fx-text-fill: #c0392b;" : " -fx-text-fill: #27ae60;"));
        status.setPrefWidth(90);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, name, categoryLabel, price, halfPrice, veg, status, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 4));

        if (SessionStore.get().hasPermission("MENU_MANAGE")) {
            Button toggle = new Button(item.available() ? "Mark Unavailable" : "Mark Available");
            toggle.setOnAction(e -> updateItem(item, new MenuDtos.UpdateItemRequest(null, null, null, null,
                    false, null, null, !item.available(), null, null, null, item.version())));
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editItemDialog(item));
            row.getChildren().addAll(toggle, edit);
        }
        return row;
    }

    private VBox buildCategorySection(MenuDtos.CategoryDto category) {
        Label name = new Label(category.name());
        name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox headerRow = new HBox(10, name, spacer);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        if (SessionStore.get().hasPermission("MENU_MANAGE")) {
            Button addItem = new Button("+ Add Item");
            addItem.setOnAction(e -> newItemDialog(category));
            headerRow.getChildren().add(addItem);
        }

        VBox section = new VBox(8, headerRow, new Separator());
        if (category.items().isEmpty()) {
            Label empty = new Label("No items in this category yet.");
            empty.setStyle("-fx-text-fill: #888; -fx-font-size: 12px;");
            section.getChildren().add(empty);
        } else {
            for (MenuDtos.ItemDto item : category.items()) {
                section.getChildren().add(buildItemRow(item));
            }
        }
        return section;
    }

    private HBox buildItemRow(MenuDtos.ItemDto item) {
        String foodTypeTag = switch (item.foodType()) {
            case "EGG" -> " 🟡";
            case "NON_VEG" -> " 🔴";
            default -> " 🟢";
        };
        String tags = foodTypeTag + (item.directSale() ? "  [Direct Sale]" : "")
                + (item.halfPrice() != null ? "  [Half/Full]" : "");
        Label name = new Label(item.name() + tags);
        name.setStyle("-fx-font-size: 14px;" + (item.available() && item.active() ? "" : " -fx-text-fill: #999;"));
        name.setPrefWidth(260);

        Label price = new Label("₹" + item.price() + (item.halfPrice() != null ? " (Half ₹" + item.halfPrice() + ")" : ""));
        price.setPrefWidth(150);

        Label availability = new Label(!item.active() ? "INACTIVE" : (item.available() ? "" : "UNAVAILABLE"));
        availability.setStyle("-fx-text-fill: #c0392b; -fx-font-size: 11px; -fx-font-weight: bold;");
        availability.setPrefWidth(90);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, name, price, availability, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 12));

        if (SessionStore.get().hasPermission("MENU_MANAGE")) {
            Button toggle = new Button(item.available() ? "Mark Unavailable" : "Mark Available");
            toggle.setOnAction(e -> updateItem(item, new MenuDtos.UpdateItemRequest(null, null, null, null,
                    false, null, null, !item.available(), null, null, null, item.version())));
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editItemDialog(item));
            row.getChildren().addAll(toggle, edit);
        }
        return row;
    }

    private void newCategoryDialog() {
        Dialog<MenuDtos.CreateCategoryRequest> dialog = new Dialog<>();
        dialog.setTitle("New Category");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("e.g. Starters, Main Course, Beverages");
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
                new Alert(Alert.AlertType.ERROR, "Enter a category name.").showAndWait();
                return null;
            }
            try {
                int displayOrder = order.getText().isBlank() ? 0 : Integer.parseInt(order.getText().trim());
                return new MenuDtos.CreateCategoryRequest(name.getText().trim(), displayOrder);
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Display order must be a whole number.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/menu/categories", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-menu-create-category");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void newItemDialog(MenuDtos.CategoryDto category) {
        Dialog<MenuDtos.CreateItemRequest> dialog = new Dialog<>();
        dialog.setTitle("New Item - " + category.name());
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("e.g. Paneer Tikka");
        TextField price = new TextField();
        price.setPromptText("e.g. 280");
        TextField taxCode = new TextField();
        taxCode.setPromptText("Tax code (optional - leave blank for the default rate)");
        ChoiceBox<String> foodType = new ChoiceBox<>(FXCollections.observableArrayList(FOOD_TYPE_DISPLAY));
        foodType.setValue("Veg");
        CheckBox directSale = new CheckBox("Direct sale (skip kitchen - e.g. bottled drinks, packaged snacks)");
        TextField halfPrice = new TextField();
        halfPrice.setPromptText("Half portion price (optional - leave blank if this item has no half portion)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Price (Full)"), price);
        grid.addRow(2, new Label("Half Price"), halfPrice);
        grid.addRow(3, new Label("Tax code"), taxCode);
        grid.addRow(4, new Label("Food Type"), foodType);
        grid.addRow(5, new Label(""), directSale);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter an item name.").showAndWait();
                return null;
            }
            try {
                BigDecimal p = new BigDecimal(price.getText().trim());
                BigDecimal half = halfPrice.getText().isBlank() ? null : new BigDecimal(halfPrice.getText().trim());
                String foodTypeCode = foodTypeCode(foodType.getValue());
                return new MenuDtos.CreateItemRequest(category.id(), name.getText().trim(), null, null, null,
                        p, taxCode.getText().isBlank() ? null : taxCode.getText().trim(), null,
                        "VEG".equals(foodTypeCode), foodTypeCode, directSale.isSelected(), half);
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid price.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/menu/items", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-menu-create-item");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editItemDialog(MenuDtos.ItemDto item) {
        Dialog<MenuDtos.UpdateItemRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit - " + item.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField name = new TextField(item.name());
        TextField price = new TextField(item.price().toPlainString());
        TextField halfPrice = new TextField(item.halfPrice() == null ? "" : item.halfPrice().toPlainString());
        halfPrice.setPromptText("Half portion price (optional)");
        TextField taxCode = new TextField(item.taxCode() == null ? "" : item.taxCode());
        ChoiceBox<String> foodType = new ChoiceBox<>(FXCollections.observableArrayList(FOOD_TYPE_DISPLAY));
        foodType.setValue(foodTypeDisplay(item.foodType()));
        CheckBox active = new CheckBox("Active (visible on the menu at all)");
        active.setSelected(item.active());
        CheckBox directSale = new CheckBox("Direct sale (skip kitchen)");
        directSale.setSelected(item.directSale());

        // Round 10: description is saved through its own dedicated PATCH (see updateItem's second
        // overload) rather than folded into UpdateItemRequest - see that record's javadoc for why.
        TextArea description = new TextArea(item.description() == null ? "" : item.description());
        description.setPromptText("Shown on receipts/KOTs and (future) customer-facing menus.");
        description.setWrapText(true);
        description.setPrefRowCount(2);
        description.setPrefColumnCount(24);
        Button suggestDescription = new Button("✨ AI Suggest");
        suggestDescription.setTooltip(new Tooltip("Uses the AI provider configured in Settings - turn on \"AI-written menu item descriptions\" there first."));
        suggestDescription.setOnAction(e -> suggestDescription(item, description));
        VBox descriptionBox = new VBox(4, description, suggestDescription);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Price (Full)"), price);
        grid.addRow(2, new Label("Half Price"), halfPrice);
        grid.addRow(3, new Label("Tax code"), taxCode);
        grid.addRow(4, new Label("Food Type"), foodType);
        grid.addRow(5, new Label("Description"), descriptionBox);
        grid.addRow(6, new Label(""), active);
        grid.addRow(7, new Label(""), directSale);
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
                BigDecimal p = new BigDecimal(price.getText().trim());
                BigDecimal half = halfPrice.getText().isBlank() ? null : new BigDecimal(halfPrice.getText().trim());
                String foodTypeCode = foodTypeCode(foodType.getValue());
                return new MenuDtos.UpdateItemRequest(name.getText().trim(), p,
                        taxCode.getText().isBlank() ? null : taxCode.getText().trim(), null, false,
                        "VEG".equals(foodTypeCode), foodTypeCode, null, active.isSelected(), directSale.isSelected(), half, item.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Enter a valid price.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> updateItem(item, request, description.getText()));
    }

    /** Calls the AI-suggest endpoint and, if it succeeds, fills {@code descriptionField} - never
     * saves anything by itself, the same "AI drafts, Save still commits" pattern as everywhere else
     * this round. A failure here (feature off, no key configured, provider error) just shows the
     * server's own explanatory message rather than silently doing nothing. */
    private void suggestDescription(MenuDtos.ItemDto item, TextArea descriptionField) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/ai/menu/" + item.id() + "/suggest-description", java.util.Map.of());
                AiDtos.SuggestDescriptionResponse response = apiClient.convert(data, AiDtos.SuggestDescriptionResponse.class);
                Platform.runLater(() -> descriptionField.setText(response.suggestedDescription()));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, ex.getMessage()).showAndWait());
            }
        }, "chefpay-menu-ai-suggest-description");
        worker.setDaemon(true);
        worker.start();
    }

    private void updateItem(MenuDtos.ItemDto item, MenuDtos.UpdateItemRequest request) {
        updateItem(item, request, null);
    }

    /** {@code newDescription} is null for every call site that has no description field in its
     * dialog (the two "Mark Available/Unavailable" toggle buttons) - only {@link #editItemDialog}
     * passes a real value, and only when it actually changed does a second PATCH fire, using the
     * version the first PATCH just returned so this can never race the main update's own optimistic lock. */
    private void updateItem(MenuDtos.ItemDto item, MenuDtos.UpdateItemRequest request, String newDescription) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.patch("/api/menu/items/" + item.id(), request);
                if (newDescription != null) {
                    MenuDtos.ItemDto updated = apiClient.convert(data, MenuDtos.ItemDto.class);
                    String trimmed = newDescription.trim();
                    String current = updated.description() == null ? "" : updated.description();
                    if (!trimmed.equals(current)) {
                        apiClient.patch("/api/menu/items/" + item.id() + "/description",
                                new MenuDtos.UpdateDescriptionRequest(trimmed, updated.version()));
                    }
                }
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING, "This item changed elsewhere - showing the latest version. Please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-menu-update-item");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
