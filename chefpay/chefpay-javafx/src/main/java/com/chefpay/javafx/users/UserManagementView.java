package com.chefpay.javafx.users;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.RestaurantDtos;
import com.chefpay.javafx.client.dto.RoleDtos;
import com.chefpay.javafx.client.dto.UserDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Staff/user account CRUD (Round 9) - lets an admin create accounts and reset a forgotten
 * password/PIN without going through raw API calls. Structured the same way every other admin
 * screen in this app is (see {@code AreaManagementView}): a flat row list built from a
 * background-thread GET, a create dialog, and an edit dialog with the same optimistic-lock
 * {@code VERSION_CONFLICT} handling. Read is gated on {@code USER_VIEW} or {@code USER_MANAGE}
 * (the server allows either), writes are gated on {@code USER_MANAGE} - this endpoint's actual
 * write permission, per {@code UserController}.
 */
public class UserManagementView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox rowsBox = new VBox(8);
    private final Label statusLabel = new Label();

    public UserManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        rowsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Users");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        if (SessionStore.get().hasPermission("USER_MANAGE")) {
            Button addUser = new Button("+ New User");
            addUser.setStyle("-fx-background-color: #2c3e50; -fx-text-fill: white; -fx-font-weight: bold;");
            addUser.setOnAction(e -> newUserDialog());
            header.getChildren().add(header.getChildren().size() - 1, addUser);
        }
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/users");
                List<UserDtos.UserDto> users = apiClient.convertList(data, UserDtos.UserDto.class);
                Platform.runLater(() -> render(users));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load users: " + ex.getMessage()));
            }
        }, "chefpay-users-admin-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<UserDtos.UserDto> users) {
        statusLabel.setText(users.size() + " user(s)");
        rowsBox.getChildren().clear();
        if (users.isEmpty()) {
            rowsBox.getChildren().add(new Label("No users yet - use \"+ New User\" above to add one."));
            return;
        }
        for (UserDtos.UserDto user : users) {
            rowsBox.getChildren().add(buildUserRow(user));
        }
    }

    private HBox buildUserRow(UserDtos.UserDto user) {
        Label username = new Label(user.username());
        username.setStyle("-fx-font-size: 14px;" + (user.active() ? "" : " -fx-text-fill: #999;"));
        username.setPrefWidth(160);

        Label displayName = new Label(user.displayName());
        displayName.setPrefWidth(200);

        Label role = new Label(user.role());
        role.setPrefWidth(140);

        Label status = new Label(user.active() ? "" : "INACTIVE");
        status.setStyle("-fx-text-fill: #c0392b; -fx-font-size: 11px; -fx-font-weight: bold;");
        status.setPrefWidth(90);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(12, username, displayName, role, status, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 12));

        if (SessionStore.get().hasPermission("USER_MANAGE") || SessionStore.get().hasPermission("BRANCH_MANAGE")) {
            Button branches = new Button("Branches");
            branches.setTooltip(new Tooltip(user.branchIds().isEmpty() ? "Unrestricted (all branches)"
                    : String.join(", ", user.branchNames())));
            branches.setOnAction(e -> branchAssignmentDialog(user));
            row.getChildren().add(branches);
        }
        if (SessionStore.get().hasPermission("USER_MANAGE")) {
            Button edit = new Button("Edit");
            edit.setOnAction(e -> editUserDialog(user));
            row.getChildren().add(edit);
        }
        return row;
    }

    /** Round 12 §3: which branches this user may work at + their default branch (skips the
     * post-login selection screen). Loads the full branch list from {@code GET /api/restaurant}
     * (already returns every branch on this restaurant, per Round 11) rather than a new endpoint. */
    private void branchAssignmentDialog(UserDtos.UserDto user) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/restaurant");
                RestaurantDtos.RestaurantDto restaurant = apiClient.convert(data, RestaurantDtos.RestaurantDto.class);
                Platform.runLater(() -> showBranchAssignmentDialog(user, restaurant.branches()));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not load branches: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-user-branches-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void showBranchAssignmentDialog(UserDtos.UserDto user, List<RestaurantDtos.BranchDto> allBranches) {
        Dialog<UserDtos.UpdateUserBranchesRequest> dialog = new Dialog<>();
        dialog.setTitle("Branch Access - " + user.username());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        VBox checks = new VBox(6);
        List<CheckBox> boxes = new ArrayList<>();
        for (RestaurantDtos.BranchDto branch : allBranches) {
            CheckBox box = new CheckBox(branch.name());
            box.setUserData(branch.id());
            box.setSelected(user.branchIds().contains(branch.id()));
            boxes.add(box);
            checks.getChildren().add(box);
        }
        ChoiceBox<String> defaultBranch = new ChoiceBox<>();
        defaultBranch.getItems().add("(Always ask)");
        defaultBranch.getItems().addAll(allBranches.stream().map(RestaurantDtos.BranchDto::name).toList());
        String currentDefaultName = allBranches.stream()
                .filter(b -> b.id().equals(user.defaultBranchId())).map(RestaurantDtos.BranchDto::name)
                .findFirst().orElse("(Always ask)");
        defaultBranch.setValue(currentDefaultName);

        Label help = new Label("No branches checked = unrestricted (this user can access every branch).");
        help.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        help.setWrapText(true);
        help.setMaxWidth(320);

        VBox content = new VBox(12, help, checks, new Label("Default branch (skips the selection screen):"), defaultBranch);
        content.setPadding(new Insets(16));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            List<UUID> selectedIds = boxes.stream().filter(CheckBox::isSelected)
                    .map(b -> (UUID) b.getUserData()).toList();
            UUID defaultId = allBranches.stream()
                    .filter(b -> b.name().equals(defaultBranch.getValue())).map(RestaurantDtos.BranchDto::id)
                    .findFirst().orElse(null);
            return new UserDtos.UpdateUserBranchesRequest(selectedIds, defaultId, user.version());
        });

        dialog.showAndWait().ifPresent(request -> {
            Thread worker = new Thread(() -> {
                try {
                    apiClient.patch("/api/users/" + user.id() + "/branches", request);
                    Platform.runLater(this::reload);
                } catch (ApiException ex) {
                    Platform.runLater(() -> {
                        if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                            reload();
                            new Alert(Alert.AlertType.WARNING, "This user changed elsewhere - showing the latest version. Please retry.").showAndWait();
                        } else {
                            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                        }
                    });
                }
            }, "chefpay-user-branches-save");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void loadRolesAndThen(Consumer<List<String>> callback) {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/roles");
                List<RoleDtos.RoleDto> roles = apiClient.convertList(data, RoleDtos.RoleDto.class);
                List<String> names = roles.stream().map(RoleDtos.RoleDto::name).toList();
                Platform.runLater(() -> callback.accept(names));
            } catch (ApiException ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Could not load roles: " + ex.getMessage()).showAndWait());
            }
        }, "chefpay-roles-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void newUserDialog() {
        loadRolesAndThen(roleNames -> {
            Dialog<UserDtos.CreateUserRequest> dialog = new Dialog<>();
            dialog.setTitle("New User");
            ButtonType createType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

            TextField username = new TextField();
            username.setPromptText("Login username");
            TextField displayName = new TextField();
            displayName.setPromptText("Full name shown in the app");
            PasswordField password = new PasswordField();
            password.setPromptText("Password");
            TextField pin = new TextField();
            pin.setPromptText("Optional PIN login");
            ChoiceBox<String> role = new ChoiceBox<>();
            role.getItems().addAll(roleNames);
            if (!roleNames.isEmpty()) {
                role.getSelectionModel().selectFirst();
            }

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(16));
            grid.addRow(0, new Label("Username"), username);
            grid.addRow(1, new Label("Display name"), displayName);
            grid.addRow(2, new Label("Password"), password);
            grid.addRow(3, new Label("PIN (optional)"), pin);
            grid.addRow(4, new Label("Role"), role);
            dialog.getDialogPane().setContent(grid);

            dialog.setResultConverter(button -> {
                if (button != createType) {
                    return null;
                }
                if (username.getText().isBlank() || displayName.getText().isBlank() || password.getText().isBlank()) {
                    new Alert(Alert.AlertType.ERROR, "Username, display name and password are required.").showAndWait();
                    return null;
                }
                if (role.getValue() == null) {
                    new Alert(Alert.AlertType.ERROR, "Select a role.").showAndWait();
                    return null;
                }
                String pinValue = pin.getText().isBlank() ? null : pin.getText().trim();
                return new UserDtos.CreateUserRequest(username.getText().trim(), displayName.getText().trim(),
                        password.getText(), pinValue, role.getValue());
            });

            dialog.showAndWait().ifPresent(request -> {
                Thread worker = new Thread(() -> {
                    try {
                        apiClient.post("/api/users", request);
                        Platform.runLater(this::reload);
                    } catch (ApiException ex) {
                        Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
                    }
                }, "chefpay-user-create");
                worker.setDaemon(true);
                worker.start();
            });
        });
    }

    private void editUserDialog(UserDtos.UserDto user) {
        loadRolesAndThen(roleNames -> {
            Dialog<UserDtos.UpdateUserRequest> dialog = new Dialog<>();
            dialog.setTitle("Edit - " + user.username());
            ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

            TextField displayName = new TextField(user.displayName());
            ChoiceBox<String> role = new ChoiceBox<>();
            role.getItems().addAll(roleNames);
            role.getSelectionModel().select(user.role());
            CheckBox active = new CheckBox("Active");
            active.setSelected(user.active());
            PasswordField newPassword = new PasswordField();
            newPassword.setPromptText("Leave blank to keep current password");
            TextField newPin = new TextField();
            newPin.setPromptText("Leave blank to keep current PIN");

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(16));
            grid.addRow(0, new Label("Display name"), displayName);
            grid.addRow(1, new Label("Role"), role);
            grid.addRow(2, new Label(""), active);
            grid.addRow(3, new Label("New Password"), newPassword);
            grid.addRow(4, new Label("New PIN"), newPin);
            dialog.getDialogPane().setContent(grid);

            dialog.setResultConverter(button -> {
                if (button != saveType) {
                    return null;
                }
                if (displayName.getText().isBlank()) {
                    new Alert(Alert.AlertType.ERROR, "Display name cannot be blank.").showAndWait();
                    return null;
                }
                if (role.getValue() == null) {
                    new Alert(Alert.AlertType.ERROR, "Select a role.").showAndWait();
                    return null;
                }
                String newPasswordValue = newPassword.getText().isBlank() ? null : newPassword.getText();
                String newPinValue = newPin.getText().isBlank() ? null : newPin.getText().trim();
                return new UserDtos.UpdateUserRequest(displayName.getText().trim(), role.getValue(),
                        active.isSelected(), newPasswordValue, newPinValue, user.version());
            });

            dialog.showAndWait().ifPresent(request -> updateUser(user, request));
        });
    }

    private void updateUser(UserDtos.UserDto user, UserDtos.UpdateUserRequest request) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/users/" + user.id(), request);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING, "This user changed elsewhere - showing the latest version. Please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-user-update");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
