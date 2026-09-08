package com.chefpay.javafx.login;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Small widgets shared by every login screen in this package - extracted out of {@link LoginView}
 * (Round 12's original home for all of these) so the Phase 2 POS first-run flow
 * ({@link PosLoginFlowView}'s user-code + PIN step) can reuse the exact same PIN keypad and
 * field/button chrome rather than a second, drift-prone copy of it. Behavior/appearance is
 * unchanged from what {@link LoginView}'s "Quick PIN" tab always looked like - this is a pure
 * extraction, not a redesign.
 */
final class LoginUiKit {

    private LoginUiKit() {
    }

    /**
     * Wraps a text control in an icon-prefixed pill so it reads as one field with a leading icon
     * (JavaFX's {@code TextField} has no built-in leading-graphic slot) - the field itself is made
     * borderless/transparent and the surrounding {@code HBox} owns the border/background instead.
     */
    static HBox fieldWithIcon(String icon, TextField field) {
        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 16px; -fx-min-width: 20px; -fx-alignment: center;");

        field.setStyle("-fx-background-color: transparent; -fx-border-width: 0; -fx-font-size: 14px;");
        HBox.setHgrow(field, Priority.ALWAYS);

        HBox wrapper = new HBox(10, iconLabel, field);
        wrapper.setAlignment(Pos.CENTER_LEFT);
        wrapper.setPadding(new Insets(0, 14, 0, 14));
        wrapper.setPrefHeight(46);
        wrapper.setStyle("-fx-background-color: #f4f5f7; -fx-background-radius: 8; "
                + "-fx-border-color: #e5e8ee; -fx-border-radius: 8; -fx-border-width: 1;");

        // Focus the surrounding pill's border to mimic a native focused-field affordance.
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> wrapper.setStyle(
                "-fx-background-color: #f4f5f7; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-width: "
                        + (isFocused ? "2" : "1") + "; -fx-border-color: " + (isFocused ? "#4f8cff" : "#e5e8ee") + ";"));
        return wrapper;
    }

    static Button bigButton(String text) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setPrefHeight(48);
        button.setStyle("-fx-background-color: #4f8cff; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-radius: 8;");
        return button;
    }

    /** A 1-9/0 touch keypad that writes into {@code target} through the same
     * {@link javafx.scene.control.TextInputControl} API a physical keyboard keystroke uses
     * (append on digit, delete-previous on Backspace) - touch and keyboard input can never disagree
     * because there is only ever one text model being mutated. Buttons are sized generously
     * (64x56) for reliable touch targets without looking oversized next to the rest of the form. */
    static GridPane buildNumericKeypad(PasswordField target) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);
        String[] layout = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
        for (int i = 0; i < layout.length; i++) {
            Button key = numericKey(layout[i], target);
            grid.add(key, i % 3, i / 3);
        }
        // The star key has no special meaning for a numeric PIN (it's appended as a plain
        // character like any other key, same as a physical keyboard could already type) - it's
        // here purely so the bottom row fills all three columns like a standard phone keypad
        // instead of leaving the first slot empty.
        Button star = numericKey("*", target);
        grid.add(star, 0, 3);
        Button zero = numericKey("0", target);
        grid.add(zero, 1, 3);
        Button backspace = new Button("⌫");
        backspace.setPrefSize(64, 56);
        backspace.setStyle(numericKeyStyle());
        backspace.setOnAction(e -> {
            String text = target.getText();
            if (text != null && !text.isEmpty()) {
                target.deleteText(text.length() - 1, text.length());
            }
        });
        grid.add(backspace, 2, 3);
        return grid;
    }

    private static Button numericKey(String digit, PasswordField target) {
        Button key = new Button(digit);
        key.setPrefSize(64, 56);
        key.setStyle(numericKeyStyle());
        // Same call a physical '0'-'9' keystroke triggers on a focused TextInputControl - touch and
        // keyboard both end up appending exactly one digit to the exact same underlying text.
        key.setOnAction(e -> target.appendText(digit));
        return key;
    }

    private static String numericKeyStyle() {
        return "-fx-font-size: 20px; -fx-font-weight: bold; -fx-background-color: #f4f5f7; "
                + "-fx-text-fill: #1b1f27; -fx-background-radius: 8; -fx-border-color: #e5e8ee; -fx-border-radius: 8;";
    }
}
