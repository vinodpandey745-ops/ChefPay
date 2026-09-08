package com.chefpay.javafx.roles;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.PermissionDtos;
import com.chefpay.javafx.client.dto.RoleDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Role &amp; Permission management screen (Round 9) - lets an admin see every role and edit exactly
 * which permission codes it grants, closing the "admin has all access, cashier has limited access"
 * gap that previously required raw API calls. Read (the role list) is gated the same way the
 * {@code GET /api/roles} endpoint is ({@code USER_VIEW} or {@code USER_MANAGE} or {@code ROLE_MANAGE});
 * the "Manage Permissions" edit affordance is gated on {@code ROLE_MANAGE}, this screen's actual
 * write permission, per {@code RoleController}/{@code PermissionController}.
 *
 * <p>Follows the same flat row list + dialog + optimistic-lock {@code VERSION_CONFLICT} idiom as
 * {@code AreaManagementView} - the only structural difference is the dialog body is a scrollable
 * checklist of every known permission code rather than plain text fields.
 */
public class RoleManagementView {

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox rowsBox = new VBox(8);
    private final Label statusLabel = new Label();

    /** The full permission catalog, refreshed alongside the role list so a new dialog always reflects the latest set. */
    private List<PermissionDtos.PermissionDto> catalog = List.of();

    public RoleManagementView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        rowsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Roles & Permissions");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, spacer, statusLabel, refresh);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        Thread worker = new Thread(() -> {
            try {
                var rolesData = apiClient.get("/api/roles");
                List<RoleDtos.RoleDto> roles = apiClient.convertList(rolesData, RoleDtos.RoleDto.class);

                List<PermissionDtos.PermissionDto> permissions = List.of();
                if (SessionStore.get().hasPermission("ROLE_MANAGE")) {
                    var permissionsData = apiClient.get("/api/permissions");
                    permissions = apiClient.convertList(permissionsData, PermissionDtos.PermissionDto.class);
                }

                List<PermissionDtos.PermissionDto> loadedCatalog = permissions;
                Platform.runLater(() -> {
                    this.catalog = loadedCatalog;
                    render(roles);
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load roles: " + ex.getMessage()));
            }
        }, "chefpay-roles-admin-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<RoleDtos.RoleDto> roles) {
        statusLabel.setText(roles.size() + " role(s)");
        rowsBox.getChildren().clear();
        if (roles.isEmpty()) {
            rowsBox.getChildren().add(new Label("No roles configured."));
            return;
        }
        for (RoleDtos.RoleDto role : roles) {
            rowsBox.getChildren().add(buildRoleRow(role));
        }
    }

    private VBox buildRoleRow(RoleDtos.RoleDto role) {
        Label name = new Label(role.name());
        name.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        name.setPrefWidth(200);

        Label description = new Label(role.description() == null ? "" : role.description());
        description.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        description.setPrefWidth(320);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox top = new HBox(12, name, description, spacer);
        top.setAlignment(Pos.CENTER_LEFT);

        if (SessionStore.get().hasPermission("ROLE_MANAGE")) {
            Button manage = new Button("Manage Permissions");
            manage.setOnAction(e -> managePermissionsDialog(role));
            top.getChildren().add(manage);
        }

        Label grants = new Label(role.permissions().isEmpty()
                ? "No permissions granted."
                : String.join(", ", role.permissions()));
        grants.setStyle("-fx-text-fill: #999; -fx-font-size: 11px;");
        grants.setWrapText(true);

        VBox row = new VBox(4, top, grants);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setStyle("-fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");
        return row;
    }

    private void managePermissionsDialog(RoleDtos.RoleDto role) {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle("Manage Permissions - " + role.name());
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        VBox checklistBox = new VBox(6);
        checklistBox.setPadding(new Insets(12));
        List<CheckBox> checkBoxes = new ArrayList<>();
        for (PermissionDtos.PermissionDto permission : catalog) {
            CheckBox checkBox = new CheckBox(permission.code());
            checkBox.setSelected(role.permissions().contains(permission.code()));
            checkBox.setUserData(permission.code());

            VBox entry = new VBox(2, checkBox);
            if (permission.description() != null && !permission.description().isBlank()) {
                Label desc = new Label(permission.description());
                desc.setStyle("-fx-text-fill: #999; -fx-font-size: 11px;");
                desc.setPadding(new Insets(0, 0, 0, 24));
                entry.getChildren().add(desc);
            }
            checklistBox.getChildren().add(entry);
            checkBoxes.add(checkBox);
        }

        ScrollPane scroll = new ScrollPane(checklistBox);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(420, 420);
        dialog.getDialogPane().setContent(scroll);

        dialog.setResultConverter(button -> {
            if (button != saveType) {
                return null;
            }
            List<String> codes = new ArrayList<>();
            for (CheckBox checkBox : checkBoxes) {
                if (checkBox.isSelected()) {
                    codes.add((String) checkBox.getUserData());
                }
            }
            return codes;
        });

        dialog.showAndWait().ifPresent(codes -> saveRolePermissions(role, codes));
    }

    private void saveRolePermissions(RoleDtos.RoleDto role, List<String> permissionCodes) {
        RoleDtos.UpdateRolePermissionsRequest request =
                new RoleDtos.UpdateRolePermissionsRequest(permissionCodes, role.version());
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/roles/" + role.id() + "/permissions", request);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    if ("VERSION_CONFLICT".equals(ex.getErrorCode())) {
                        reload();
                        new Alert(Alert.AlertType.WARNING, "This role changed elsewhere - showing the latest version. Please retry.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        }, "chefpay-role-permissions-update");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
