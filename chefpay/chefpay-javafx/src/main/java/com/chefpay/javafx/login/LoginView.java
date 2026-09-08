package com.chefpay.javafx.login;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.LoginPayload;
import com.chefpay.javafx.client.dto.LoginResult;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import com.chefpay.javafx.common.AppLogo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Fast login screen (requirement §8/§41): username+password for a full login, or a 4-6 digit PIN
 * for the common "quick clock-in on a shared terminal" path.
 *
 * <p>Split-panel layout (branding on the left, the form on the right) with icon-prefixed fields,
 * a "Keep me logged in" toggle, and a biometric login affordance - a UI refresh requested against a
 * reference design. Two notes on what's real vs. cosmetic here:
 * <ul>
 *   <li>"Keep me logged in" remembers the last-used <em>username</em> locally (via
 *       {@link Preferences}) and pre-fills it next launch - it deliberately does not persist the
 *       JWT or password. This backend issues short-lived bearer tokens with no refresh-token flow
 *       (ARCHITECTURE.md §7), so there is nothing safe to keep "logged in" across a process
 *       restart; skipping the username re-type is the honest version of this toggle for now.</li>
 *   <li>"Login with Touch ID" is shown but disabled, with a tooltip explaining why: JavaFX has no
 *       built-in OS biometric API, and faking a successful biometric check would be actively
 *       misleading on a payments terminal. Wiring a real one is a native-integration task
 *       (Windows Hello / macOS LocalAuthentication via JNI or a helper process) out of scope for
 *       this pass - the affordance is left in place so the option is discoverable, not hidden.</li>
 * </ul>
 */
public class LoginView {

    private static final Preferences PREFS = Preferences.userNodeForPackage(LoginView.class);
    private static final String PREF_REMEMBERED_USERNAME = "rememberedUsername";

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient = new ApiClient();
    private final Label errorLabel = new Label();

    public LoginView(Consumer<LoginResult> onSuccess) {
        root.setStyle("-fx-background-color: white;");

        HBox split = new HBox(buildBrandPanel(), buildFormPanel(onSuccess));
        HBox.setHgrow(split.getChildren().get(1), Priority.ALWAYS);
        root.setCenter(split);
    }

    // ---- left panel: branding ----

    private VBox buildBrandPanel() {
        javafx.scene.Node mark = AppLogo.imageView(72);

        Label title = new Label("Bistrodesk");
        title.setFont(Font.font("System", FontWeight.BOLD, 40));
        title.setStyle("-fx-text-fill: white;");

        Label subtitle = new Label("Restaurant POS");
        subtitle.setStyle("-fx-text-fill: #b7c0cf; -fx-font-size: 15px;");

        Label tagline = new Label("Tables, kitchen, billing and stock - one screen per shift.");
        tagline.setWrapText(true);
        tagline.setStyle("-fx-text-fill: #8a95a6; -fx-font-size: 13px;");
        tagline.setMaxWidth(280);

        VBox content = new VBox(10, mark, title, subtitle, new Region(), tagline);
        content.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(tagline, new Insets(28, 0, 0, 0));

        VBox panel = new VBox(content);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(48));
        panel.setPrefWidth(420);
        panel.setMinWidth(340);
        panel.setStyle("-fx-background-color: linear-gradient(to bottom right, #1b1f27, #262c38);");
        return panel;
    }

    // ---- right panel: the form ----

    private VBox buildFormPanel(Consumer<LoginResult> onSuccess) {
        Label welcome = new Label("Welcome back");
        welcome.setFont(Font.font("System", FontWeight.BOLD, 26));
        welcome.setStyle("-fx-text-fill: #1b1f27;");
        Label instructions = new Label("Sign in to start your shift.");
        instructions.setStyle("-fx-text-fill: #8a95a6; -fx-font-size: 13px;");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMaxWidth(380);
        // Phase 2 (PHASE2_ORG_SUBSCRIPTION_DESIGN.md Section E): the POS first-run flow (branch
        // code -> terminal select -> user code + PIN) is now the primary path for a cashier
        // terminal, so it's both first in the tab order and the tab selected by default below -
        // Username & Password and the old ambiguous Quick PIN scan remain exactly as they were for
        // anyone who still needs them (Manager/Admin login, or a not-yet-upgraded terminal).
        PosLoginFlowView posFlow = new PosLoginFlowView(apiClient, onSuccess);
        Tab posTab = new Tab("POS Terminal", posFlow.view());
        Tab passwordTab = new Tab("Username & Password", buildPasswordForm(onSuccess));
        Tab pinTab = new Tab("Quick PIN", buildPinForm(onSuccess));
        tabs.getTabs().addAll(posTab, passwordTab, pinTab);
        tabs.getSelectionModel().select(posTab);

        // Round 12 §2: keyboard/UI focus stays synchronized as the user moves between the two
        // login modes - whichever tab becomes active gets its own first field focused immediately,
        // so a cashier never has to click before typing.
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null) {
                return;
            }
            Platform.runLater(() -> {
                Parent content = (Parent) newTab.getContent();
                Node focusTarget = content.lookup(".focus-first");
                if (focusTarget != null) {
                    focusTarget.requestFocus();
                }
            });
        });

        errorLabel.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 13px;");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(380);

        Button touchId = buildTouchIdButton();

        VBox form = new VBox(18, welcome, instructions, tabs, errorLabel, buildDivider(), touchId);
        form.setAlignment(Pos.TOP_LEFT);
        form.setMaxWidth(380);

        VBox panel = new VBox(form);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(48));
        panel.setStyle("-fx-background-color: white;");
        return panel;
    }

    private HBox buildDivider() {
        Region left = new Region();
        Region right = new Region();
        left.setStyle("-fx-background-color: #e5e8ee;");
        right.setStyle("-fx-background-color: #e5e8ee;");
        left.setPrefHeight(1);
        right.setPrefHeight(1);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        Label or = new Label("or");
        or.setStyle("-fx-text-fill: #b0b6c0; -fx-font-size: 12px;");
        HBox divider = new HBox(10, left, or, right);
        divider.setAlignment(Pos.CENTER);
        return divider;
    }

    private Button buildTouchIdButton() {
        Button button = new Button("🔐  Login with Touch ID");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setPrefHeight(46);
        button.setDisable(true);
        button.setStyle("-fx-background-color: #f4f5f7; -fx-text-fill: #8a95a6; -fx-font-size: 14px; "
                + "-fx-font-weight: bold; -fx-background-radius: 8; -fx-border-color: #e5e8ee; -fx-border-radius: 8;");
        Tooltip tooltip = new Tooltip("Not available on this device - biometric login needs OS-level "
                + "integration this build doesn't include yet.");
        tooltip.setShowDelay(Duration.millis(200));
        Tooltip.install(button, tooltip);
        return button;
    }

    private VBox buildPasswordForm(Consumer<LoginResult> onSuccess) {
        TextField username = new TextField();
        username.setPromptText("Username");
        String remembered = PREFS.get(PREF_REMEMBERED_USERNAME, null);
        boolean hasRemembered = remembered != null && !remembered.isBlank();
        if (hasRemembered) {
            username.setText(remembered);
        }

        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        // Round 12 §2: whichever field should get first focus wears this marker class so the tab-
        // switch listener above (and this screen's own initial focus, below) can find it generically
        // rather than hardcoding "the username field" vs "the PIN field" in two separate places.
        (hasRemembered ? password : username).getStyleClass().add("focus-first");

        ToggleSwitch keepLoggedIn = new ToggleSwitch("Keep me logged in");
        keepLoggedIn.setSelected(hasRemembered);

        Button loginButton = LoginUiKit.bigButton("Log In");
        loginButton.setDefaultButton(true);
        loginButton.setOnAction(e -> {
            String enteredUsername = username.getText().trim();
            if (keepLoggedIn.isSelected()) {
                PREFS.put(PREF_REMEMBERED_USERNAME, enteredUsername);
            } else {
                PREFS.remove(PREF_REMEMBERED_USERNAME);
            }
            doLogin(LoginPayload.passwordLogin(enteredUsername, password.getText(), deviceName()), onSuccess, loginButton);
        });
        // Round 12 §2: Enter in the username field moves to Password rather than submitting a
        // half-filled form - TextField's onAction already fires on Enter, so this is the same
        // "keyboard-native" mechanism as everything else here, not a separate key-listener hack.
        username.setOnAction(e -> password.requestFocus());

        VBox box = new VBox(14, LoginUiKit.fieldWithIcon("👤", username), LoginUiKit.fieldWithIcon("🔒", password), keepLoggedIn, loginButton);
        box.setPadding(new Insets(20, 0, 0, 0));
        Platform.runLater((hasRemembered ? password : username)::requestFocus);
        return box;
    }

    private VBox buildPinForm(Consumer<LoginResult> onSuccess) {
        PasswordField pin = new PasswordField();
        pin.setPromptText("PIN");
        pin.setStyle("-fx-font-size: 22px; -fx-alignment: center;");
        pin.setEditable(true);
        pin.getStyleClass().add("focus-first");
        // Touchscreen numeric keypad (Round 12 §4) is a thin wrapper over this exact same
        // PasswordField - every touch button below calls the identical text-model methods a
        // physical keyboard keystroke would (appendText/deleteText), so "press physical 1" and
        // "touch 1" are provably the same input, not two parallel code paths that could drift.
        pin.setMaxWidth(220);

        Button loginButton = LoginUiKit.bigButton("Clock In");
        loginButton.setDefaultButton(true);
        loginButton.setOnAction(e -> doLogin(LoginPayload.pinLogin(pin.getText().trim(), deviceName()), onSuccess, loginButton));

        HBox pinField = LoginUiKit.fieldWithIcon("🔢", pin);
        pinField.setPrefHeight(56);
        pinField.setAlignment(Pos.CENTER_LEFT);

        GridPane keypad = LoginUiKit.buildNumericKeypad(pin);

        VBox box = new VBox(14, pinField, keypad, loginButton);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(20, 0, 0, 0));
        return box;
    }

    private void doLogin(LoginPayload payload, Consumer<LoginResult> onSuccess, Button triggerButton) {
        errorLabel.setText("");
        triggerButton.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/auth/login", payload);
                LoginResult result = apiClient.convert(data, LoginResult.class);
                SessionStore.get().establish(result.token(), result.userId(), result.username(),
                        result.displayName(), result.role(), result.permissions(), result.branches());
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    errorLabel.setText(ex.getMessage());
                    triggerButton.setDisable(false);
                });
            }
        }, "chefpay-login");
        worker.setDaemon(true);
        worker.start();
    }

    private String deviceName() {
        String host = System.getProperty("chefpay.device.name");
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "javafx-terminal";
        }
    }

    public Parent view() {
        return root;
    }

    /**
     * Minimal iOS/Material-style toggle switch. JavaFX ships no such control, and a bare
     * {@code CheckBox} doesn't match the pill-shaped switch the reference login mockup used for
     * "Keep me logged in" - this is a small self-contained stand-in rather than pulling in a UI
     * library for one control.
     */
    private static final class ToggleSwitch extends HBox {

        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private final Region track = new Region();
        private final Circle knob = new Circle(9, Color.WHITE);

        ToggleSwitch(String label) {
            track.setPrefSize(40, 22);
            track.setMinSize(40, 22);
            track.setMaxSize(40, 22);

            StackPane switchPane = new StackPane(track, knob);
            switchPane.setPrefSize(40, 22);
            StackPane.setAlignment(knob, Pos.CENTER_LEFT);
            knob.setTranslateX(-9);
            switchPane.setCursor(Cursor.HAND);
            switchPane.setOnMouseClicked(e -> selected.set(!selected.get()));

            Label text = new Label(label);
            text.setStyle("-fx-font-size: 13px; -fx-text-fill: #5a6472;");
            text.setCursor(Cursor.HAND);
            text.setOnMouseClicked(e -> selected.set(!selected.get()));

            getChildren().addAll(switchPane, text);
            setSpacing(10);
            setAlignment(Pos.CENTER_LEFT);

            selected.addListener((obs, was, isOn) -> render(isOn));
            render(false);
        }

        private void render(boolean on) {
            track.setStyle("-fx-background-color: " + (on ? "#4f8cff" : "#d5dae2") + "; -fx-background-radius: 11;");
            TranslateTransition transition = new TranslateTransition(Duration.millis(120), knob);
            transition.setToX(on ? 9 : -9);
            transition.play();
        }

        boolean isSelected() {
            return selected.get();
        }

        void setSelected(boolean value) {
            selected.set(value);
        }
    }
}
