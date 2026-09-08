package com.chefpay.javafx.audit;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.AuditDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.format.DateTimeFormatter;

/**
 * Read-only trail viewer over {@code GET /api/audit} - Phase 5's Audit slice (ARCHITECTURE.md
 * §13). The log itself has existed since Phase 1 ({@code AuditService.record}, called from auth,
 * order, billing and now inventory mutations); this is the first screen that surfaces it. Optional
 * entity-type filter only, to keep this a simple first cut - full text search/date-range filtering
 * can grow later if it's needed.
 */
public class AuditLogView {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final ListView<AuditDtos.EntryDto> entryList = new ListView<>();
    private final ComboBox<String> entityTypeFilter = new ComboBox<>();
    private final Label statusLabel = new Label();

    private int currentPage = 0;

    public AuditLogView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        entryList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(AuditDtos.EntryDto entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    return;
                }
                String reason = entry.reason() == null ? "" : "  •  " + entry.reason();
                setText(entry.timestamp().format(TS_FORMAT) + "   " + entry.userName() + "   "
                        + entry.entityType() + "  " + entry.action() + reason);
            }
        });
        root.setCenter(entryList);
    }

    private HBox buildHeader() {
        Label title = new Label("Audit Log");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        entityTypeFilter.setPromptText("All entity types");
        entityTypeFilter.getItems().addAll("Order", "OrderItem", "Payment", "InventoryItem", "MenuItem", "AppUser");
        entityTypeFilter.setOnAction(e -> { currentPage = 0; reload(); });
        Button clearFilter = new Button("Clear");
        clearFilter.setOnAction(e -> { entityTypeFilter.setValue(null); currentPage = 0; reload(); });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(12, title, entityTypeFilter, clearFilter, spacer, statusLabel, refresh);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        statusLabel.setText("Loading...");
        String entityType = entityTypeFilter.getValue();
        Thread worker = new Thread(() -> {
            try {
                StringBuilder path = new StringBuilder("/api/audit?page=" + currentPage + "&size=50");
                if (entityType != null) {
                    path.append("&entityType=").append(entityType);
                }
                var data = apiClient.get(path.toString());
                AuditDtos.PageDto page = apiClient.convert(data, AuditDtos.PageDto.class);
                Platform.runLater(() -> renderPage(page));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load audit log: " + ex.getMessage()));
            }
        }, "chefpay-audit-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderPage(AuditDtos.PageDto page) {
        statusLabel.setText("Page " + (page.page() + 1) + " of " + Math.max(page.totalPages(), 1)
                + "  •  " + page.totalElements() + " total entries");
        entryList.getItems().setAll(page.entries());
    }

    public Parent view() {
        return root;
    }
}
