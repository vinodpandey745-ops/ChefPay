package com.chefpay.javafx.login;

import com.chefpay.javafx.client.dto.LoginResult;
import com.chefpay.javafx.common.AppLogo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Round 12 §3: shown right after a successful login, but only when {@code AuthController}'s
 * effective branch list for this user has 2+ entries and neither their {@code defaultBranchId}
 * nor this terminal's previously-remembered branch resolves unambiguously (see
 * {@code ChefPayDesktopApp#resolveBranchThenShowShell}) - a single-branch restaurant, or a user
 * restricted to exactly one branch, never sees this screen at all. Deliberately a plain list of
 * big touch-friendly buttons rather than a dropdown: this is a once-per-login decision on
 * (typically) a handful of branches, not a frequent in-shift action like {@code ShellView}'s
 * header switcher.
 */
public class BranchSelectionView {

    private final VBox root = new VBox();

    public BranchSelectionView(List<LoginResult.BranchSummary> branches, Consumer<UUID> onSelected) {
        root.setAlignment(Pos.CENTER);
        root.setSpacing(18);
        root.setPadding(new Insets(48));
        root.setStyle("-fx-background-color: white;");

        if (AppLogo.isAvailable()) {
            root.getChildren().add(AppLogo.imageView(56));
        }

        Label title = new Label("Choose a Branch");
        title.setFont(Font.font("System", FontWeight.BOLD, 24));
        title.setStyle("-fx-text-fill: #1b1f27;");
        Label subtitle = new Label("Select which branch you're working at for this session.");
        subtitle.setStyle("-fx-text-fill: #8a95a6; -fx-font-size: 13px;");

        VBox buttons = new VBox(12);
        buttons.setAlignment(Pos.CENTER);
        buttons.setMaxWidth(360);
        for (LoginResult.BranchSummary branch : branches) {
            Button button = new Button(branch.name());
            button.setMaxWidth(Double.MAX_VALUE);
            button.setPrefHeight(56);
            button.setStyle("-fx-background-color: #4f8cff; -fx-text-fill: white; -fx-font-size: 16px; "
                    + "-fx-font-weight: bold; -fx-background-radius: 8;");
            button.setOnAction(e -> onSelected.accept(branch.id()));
            buttons.getChildren().add(button);
        }

        VBox content = new VBox(14, title, subtitle, buttons);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(400);
        root.getChildren().add(content);
    }

    public Parent view() {
        return root;
    }
}
