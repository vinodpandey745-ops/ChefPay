package com.chefpay.javafx.specialnotes;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.SpecialNoteDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;

/**
 * Reusable special-instruction preset CRUD (Round 8) - the quick-pick source for
 * {@code OrderItem#getSpecialInstructions()} on order taking (e.g. "Extra Spicy", "No Onion").
 * Same shape as {@code AreaManagementView}: a flat row list ordered by {@code displayOrder} (the
 * server already returns it pre-sorted - never re-sorted here), a create dialog, and an edit
 * dialog with the same optimistic-lock {@code VERSION_CONFLICT} handling every other admin screen
 * in this app uses. Unlike Areas, this endpoint's write permission is {@code MENU_MANAGE} (per
 * {@code SpecialNoteController}) - do not gate this screen's create/edit on {@code TABLE_MANAGE}.
 */
public class SpecialNoteManagementView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox rowsBox = new VBox(8);
    private final Label statusLabel = new Label();

    public SpecialNoteManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        rowsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Special Notes");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("MENU_MANAGE")) {
            Button addNote = new Button("+ New Special Note");
            addNote.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addNote.setOnAction(e -> newNoteDialog());
            header.getChildren().add(header.getChildren().size() - 1, addNote);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/special-notes");
                List<SpecialNoteDtos.SpecialNoteDto> notes = apiClient.convertList(data, SpecialNoteDtos.SpecialNoteDto.class);
                Platform.runLater(() -> render(notes));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load special notes: " + ex.getMessage()));
            }
        }, "chefpay-specialnotes-admin-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<SpecialNoteDtos.SpecialNoteDto> notes) {
        statusLabel.setText(notes.size() + " special note(s)");
        rowsBox.getChildren().clear();
        if (notes.isEmpty()) {
            rowsBox.getChildren().add(new Label("No special notes yet - use \"+ New Special Note\" above to add one."));
            return;
        }
        for (SpecialNoteDtos.SpecialNoteDto note : notes) {
            rowsBox.getChildren().add(buildNoteRow(note));
        }
    }

    private HBox buildNoteRow(SpecialNoteDtos.SpecialNoteDto note) {
        Label text = new Label(note.text());
        text.setStyle("-fx-font-size: 14px;" + (note.active() ? "" : " -fx-text-fill: #999;"));
        text.setPrefWidth(280);

        Label order = new Label("Order: " + note.displayOrder());
        order.setPrefWidth(100);

        Label status = new Label(note.active() ? "" : "INACTIVE");
        status.setStyle("-fx-text-fill: #c0392b; -fx-font-size: 11px; -fx-font-weight: bold;");
        status.setPrefWidth(90);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, text, order, status, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 12));

        if (SessionStore.get().hasPermission("MENU_MANAGE")) {
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editNoteDialog(note));
            row.getChildren().add(edit);
        }
        return row;
    }

    private void newNoteDialog() {
        Dialog<SpecialNoteDtos.CreateSpecialNoteRequest> dialog = new Dialog<>();
        dialog.setTitle("New Special Note");
        ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField text = new TextField();
        text.setPromptText("e.g. Extra Spicy, No Onion");
        TextField order = new TextField("0");
        order.setPromptText("Display order (lower shows first)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Text"), text);
        grid.addRow(1, new Label("Display order"), order);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != createType) {
                return null;
            }
            if (text.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Enter the note text.").showAndWait();
                return null;
            }
            try {
                int displayOrder = order.getText().isBlank() ? 0 : Integer.parseInt(order.getText().trim());
                // branchId left null - the server resolves the single seeded branch on create.
                return new SpecialNoteDtos.CreateSpecialNoteRequest(null, text.getText().trim(), displayOrder);
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Display order must be a whole number.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.post("/api/special-notes", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                }
            }, "chefpay-specialnote-create");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void editNoteDialog(SpecialNoteDtos.SpecialNoteDto note) {
        Dialog<SpecialNoteDtos.UpdateSpecialNoteRequest> dialog = new Dialog<>();
        dialog.setTitle("Edit - " + note.text());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField text = new TextField(note.text());
        TextField order = new TextField(String.valueOf(note.displayOrder()));
        CheckBox active = new CheckBox("Active");
        active.setSelected(note.active());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Text"), text);
        grid.addRow(1, new Label("Display order"), order);
        grid.addRow(2, new Label(""), active);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            if (text.getText().isBlank()) {
                new Alert(Alert.AlertType.ERROR, "Text cannot be blank.").showAndWait();
                return null;
            }
            try {
                int displayOrder = order.getText().isBlank() ? 0 : Integer.parseInt(order.getText().trim());
                return new SpecialNoteDtos.UpdateSpecialNoteRequest(text.getText().trim(), displayOrder, active.isSelected(), note.version());
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Display order must be a whole number.").showAndWait();
                return null;
            }
        });

        dialog.showAndWait().ifPresent(request -> updateNote(note, request));
    }

    private void updateNote(SpecialNoteDtos.SpecialNoteDto note, SpecialNoteDtos.UpdateSpecialNoteRequest request) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/special-notes/" + note.id(), request);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING, "This note changed elsewhere - showing the latest version. Please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-specialnote-update");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
