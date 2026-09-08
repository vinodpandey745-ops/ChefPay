package com.chefpay.javafx.customers;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.CustomerDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Guest directory screen (Phase 5c) - backs {@code GET/POST/PATCH /api/customers}. Deliberately a
 * flat searchable list rather than a full CRM (no visit history detail, no loyalty points) - the
 * genuinely useful thing a small/mid restaurant needs day-to-day is "who is this person on the
 * phone and have they ordered before", which this screen and the Delivery/Pickup quick-order
 * dialog's phone lookup both cover. {@code visitCount}/{@code totalSpend} are shown read-only;
 * they're not auto-incremented yet since {@code Order} has no FK to {@code Customer} this round
 * (see {@code Customer}'s javadoc) - they exist so a manager can hand-track VIP guests today, ready
 * to be wired to real order data once that FK lands.
 */
public class CustomersView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox customerRows = new VBox(8);
    private final Label statusLabel = new Label();
    private final TextField searchField = new TextField();

    public CustomersView(ApiClient apiClient) {
        this.apiClient = apiClient;
        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(customerRows);
        scroll.setFitToWidth(true);
        customerRows.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private VBox buildHeader() {
        Label title = new Label("Customers");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox titleRow = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("CUSTOMER_MANAGE")) {
            Button addCustomer = new Button("+ New Customer");
            addCustomer.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addCustomer.setOnAction(e -> newCustomerDialog());
            titleRow.getChildren().add(titleRow.getChildren().size() - 1, addCustomer);
        }
        titleRow.setAlignment(Pos.CENTER_LEFT);

        searchField.setPromptText("Search by name or phone...");
        searchField.setPrefWidth(320);
        searchField.setOnAction(e -> reload());
        Button search = new Button("Search");
        search.setOnAction(e -> reload());
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            searchField.clear();
            reload();
        });
        HBox searchRow = new HBox(8, searchField, search, clear);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(10, titleRow, searchRow);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        String query = searchField.getText();
        String path = (query == null || query.isBlank())
                ? "/api/customers"
                : "/api/customers?query=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get(path);
                List<CustomerDtos.CustomerDto> customers = apiClient.convertList(data, CustomerDtos.CustomerDto.class);
                Platform.runLater(() -> render(customers));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load customers: " + ex.getMessage()));
            }
        }, "chefpay-customers-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<CustomerDtos.CustomerDto> customers) {
        statusLabel.setText(customers.size() + " customer(s)");
        customerRows.getChildren().clear();
        if (customers.isEmpty()) {
            customerRows.getChildren().add(new Label("No customers found - use \"+ New Customer\" above to add one."));
            return;
        }
        Label columnHeader = new Label(String.format("%-24s %-16s %-24s %-10s %-12s %-16s",
                "Name", "Phone", "Email", "Visits", "Total Spend", "Last Visit"));
        columnHeader.setStyle("-fx-font-family: monospace; -fx-font-weight: bold; -fx-text-fill: #888; -fx-font-size: 11px;");
        customerRows.getChildren().add(columnHeader);
        for (CustomerDtos.CustomerDto customer : customers) {
            customerRows.getChildren().add(buildCustomerRow(customer));
        }
    }

    private HBox buildCustomerRow(CustomerDtos.CustomerDto customer) {
        Label name = new Label(customer.name());
        name.setPrefWidth(180);
        Label phone = new Label(customer.phone() == null ? "-" : customer.phone());
        phone.setPrefWidth(120);
        Label email = new Label(customer.email() == null ? "-" : customer.email());
        email.setPrefWidth(180);
        Label visits = new Label(String.valueOf(customer.visitCount()));
        visits.setPrefWidth(70);
        Label spend = new Label("₹" + customer.totalSpend());
        spend.setPrefWidth(100);
        Label lastVisit = new Label(customer.lastVisitAt() == null ? "-"
                : customer.lastVisitAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        lastVisit.setPrefWidth(120);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, name, phone, email, visits, spend, lastVisit, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-border-color: #eee; -fx-border-width: 0 0 1 0;");

        if (SessionStore.get().hasPermission("CUSTOMER_MANAGE")) {
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editCustomerDialog(customer));
            row.getChildren().add(edit);
        }
        return row;
    }

    private void newCustomerDialog() {
        Dialog<CustomerDtos.CreateCustomerRequest> dialog = new Dialog<>();
        dialog.setTitle("New Customer");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText("Guest name");
        TextField phone = new TextField();
        phone.setPromptText("Phone (optional)");
        TextField email = new TextField();
        email.setPromptText("Email (optional)");
        TextArea notes = new TextArea();
        notes.setPromptText("Notes (optional)");
        notes.setPrefRowCount(3);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Phone"), phone);
        grid.addRow(2, new Label("Email"), email);
        grid.addRow(3, new Label("Notes"), notes);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter a customer name.").showAndWait();
                return null;
            }
            return new CustomerDtos.CreateCustomerRequest(name.getText().trim(), blankToNull(phone.getText()),
                    blankToNull(email.getText()), blankToNull(notes.getText()));
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/customers", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-customer-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editCustomerDialog(CustomerDtos.CustomerDto customer) {
        Dialog<CustomerDtos.UpdateCustomerRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit - " + customer.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField name = new TextField(customer.name());
        TextField phone = new TextField(customer.phone() == null ? "" : customer.phone());
        TextField email = new TextField(customer.email() == null ? "" : customer.email());
        TextArea notes = new TextArea(customer.notes() == null ? "" : customer.notes());
        notes.setPrefRowCount(3);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Phone"), phone);
        grid.addRow(2, new Label("Email"), email);
        grid.addRow(3, new Label("Notes"), notes);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            if (name.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Name cannot be blank.").showAndWait();
                return null;
            }
            return new CustomerDtos.UpdateCustomerRequest(name.getText().trim(), blankToNull(phone.getText()),
                    blankToNull(email.getText()), blankToNull(notes.getText()), customer.version());
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.patch("/api/customers/" + customer.id(), request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> {
                        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                            reload();
                            new Alert(Alert.AlertType.WARNING, "This customer changed elsewhere - showing the latest version. Please retry.").showAndWait();
                        } else {
                            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                        }
                    });
                }
            }, "chefpay-customer-update");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Parent view() {
        return root;
    }
}
