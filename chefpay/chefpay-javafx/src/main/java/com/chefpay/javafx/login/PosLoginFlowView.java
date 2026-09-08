package com.chefpay.javafx.login;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.dto.FirstRunDtos.BranchByCodeResult;
import com.chefpay.javafx.client.dto.FirstRunDtos.TerminalOption;
import com.chefpay.javafx.client.dto.LoginPayload;
import com.chefpay.javafx.client.dto.LoginResult;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Phase 2 POS first-run flow (PHASE2_ORG_SUBSCRIPTION_DESIGN.md Section E): Branch Code entry
 * -&gt; Terminal Select (skipped automatically when the branch has exactly one active terminal,
 * matching the existing {@code BranchSelectionView}'s "skip if only one" precedent) -&gt; User Code
 * + PIN, reusing {@link LoginUiKit}'s numeric keypad completely unchanged from what {@code
 * LoginView}'s "Quick PIN" tab has always looked like.
 *
 * <p>The branch/terminal choice is remembered locally via {@link PosIdentityStore} so it isn't
 * re-entered every shift - only the PIN step repeats on a normal restart. A "Not your terminal?"
 * link on that final step clears the remembered choice and returns to Branch Code entry.
 *
 * <p>This is a self-contained step sequence embedded as one tab's content in {@link LoginView} -
 * on a successful login it calls the exact same {@code onSuccess} callback {@code LoginView}
 * itself was given, so what happens after a successful login (session establishment, routing to
 * the shell) is identical regardless of which of the three tabs was used.
 */
public class PosLoginFlowView {

    private final ApiClient apiClient;
    private final Consumer<LoginResult> onSuccess;
    private final StackPane root = new StackPane();

    private UUID branchId;
    private String branchName;
    private UUID terminalId;
    private String terminalName;

    public PosLoginFlowView(ApiClient apiClient, Consumer<LoginResult> onSuccess) {
        this.apiClient = apiClient;
        this.onSuccess = onSuccess;
        root.setAlignment(Pos.TOP_CENTER);
        restoreOrStart();
    }

    public Parent view() {
        return root;
    }

    private void showStep(javafx.scene.Node content) {
        root.getChildren().setAll(content);
    }

    /** A terminal already remembered on this machine (from a previous run, or from just having
     * completed Terminal Select this session) skips straight to the user-code + PIN step - only
     * PIN entry is meant to repeat on a normal restart. Otherwise, tries the single-default-branch
     * shortcut before ever showing manual code entry - see {@link #showCheckingDefaultBranch}. */
    private void restoreOrStart() {
        UUID rememberedBranch = PosIdentityStore.getBranchId();
        UUID rememberedTerminal = PosIdentityStore.getTerminalId();
        if (rememberedBranch != null && rememberedTerminal != null) {
            branchId = rememberedBranch;
            branchName = PosIdentityStore.getBranchName();
            terminalId = rememberedTerminal;
            terminalName = PosIdentityStore.getTerminalName();
            showUserCodeStep();
        } else {
            showCheckingDefaultBranch();
        }
    }

    /** Fix (this round): mirrors the identical fix in chefpay-web's {@code PosFirstRunFlow} - a
     * single-branch restaurant (the common single-location install) should never have to type a
     * branch code at all, matching this flow's own Terminal Select step (which already auto-skips
     * when there's exactly one terminal) and the design doc's "a single-branch, single-terminal
     * restaurant never sees an extra screen" principle. Silently tries the PUBLIC {@code GET
     * /api/branches/default} (200 only when exactly one active branch exists) before ever showing
     * the manual code-entry screen; ANY failure - zero or 2+ active branches, or a network error -
     * falls through to {@link #showBranchCodeStep} exactly as before, with no visible error, since
     * trying the shortcut was never something the operator asked for. */
    private void showCheckingDefaultBranch() {
        Label title = new Label("Setting up this terminal…");
        title.setFont(Font.font("System", FontWeight.BOLD, 16));
        title.setStyle("-fx-text-fill: #8a95a6;");
        VBox box = new VBox(16, title);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(60, 0, 0, 0));
        showStep(box);

        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/branches/default");
                BranchByCodeResult result = apiClient.convert(data, BranchByCodeResult.class);
                Platform.runLater(() -> {
                    branchId = result.branchId();
                    branchName = result.branchName();
                    loadTerminals();
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> showBranchCodeStep(null));
            }
        }, "chefpay-default-branch");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- step 1: branch code ----

    private void showBranchCodeStep(String initialError) {
        Label title = new Label("Enter Branch Code");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setStyle("-fx-text-fill: #1b1f27;");
        Label subtitle = new Label("Ask your manager for this restaurant's branch code.");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: #8a95a6; -fx-font-size: 13px;");

        TextField codeField = new TextField();
        codeField.setPromptText("Branch code");
        codeField.getStyleClass().add("focus-first");

        Label error = new Label(initialError == null ? "" : initialError);
        error.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 13px;");
        error.setWrapText(true);
        error.setMaxWidth(320);

        Button continueButton = LoginUiKit.bigButton("Continue");
        continueButton.setDefaultButton(true);

        Runnable submit = () -> {
            String code = codeField.getText() == null ? "" : codeField.getText().trim();
            if (code.isBlank()) {
                error.setText("Enter a branch code to continue.");
                return;
            }
            error.setText("");
            continueButton.setDisable(true);
            codeField.setDisable(true);
            Thread worker = new Thread(() -> {
                try {
                    var data = apiClient.get("/api/branches/by-code/" + code);
                    BranchByCodeResult result = apiClient.convert(data, BranchByCodeResult.class);
                    Platform.runLater(() -> {
                        branchId = result.branchId();
                        branchName = result.branchName();
                        loadTerminals();
                    });
                } catch (ApiException ex) {
                    Platform.runLater(() -> {
                        error.setText(ex.getMessage());
                        continueButton.setDisable(false);
                        codeField.setDisable(false);
                        codeField.requestFocus();
                    });
                }
            }, "chefpay-branch-code");
            worker.setDaemon(true);
            worker.start();
        };
        continueButton.setOnAction(e -> submit.run());
        codeField.setOnAction(e -> submit.run());

        VBox box = new VBox(16, title, subtitle, LoginUiKit.fieldWithIcon("🏢", codeField), error, continueButton);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(340);
        box.setPadding(new Insets(20, 0, 0, 0));
        showStep(box);
        Platform.runLater(codeField::requestFocus);
    }

    // ---- step 2: terminal select ----

    private void loadTerminals() {
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get("/api/branches/" + branchId + "/terminals");
                List<TerminalOption> terminals = apiClient.convertList(data, TerminalOption.class);
                Platform.runLater(() -> {
                    if (terminals.size() == 1) {
                        TerminalOption only = terminals.get(0);
                        selectTerminal(only.id(), only.name());
                    } else if (terminals.isEmpty()) {
                        showBranchCodeStep("This branch has no active terminals set up yet. "
                                + "Ask your manager to add one, then try again.");
                    } else {
                        showTerminalSelectStep(terminals);
                    }
                });
            } catch (ApiException ex) {
                Platform.runLater(() -> showBranchCodeStep(ex.getMessage()));
            }
        }, "chefpay-terminal-list");
        worker.setDaemon(true);
        worker.start();
    }

    private void showTerminalSelectStep(List<TerminalOption> terminals) {
        Label title = new Label("Select Terminal");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setStyle("-fx-text-fill: #1b1f27;");
        Label subtitle = new Label(branchName == null ? "Which terminal is this?" : branchName + " - which terminal is this?");
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: #8a95a6; -fx-font-size: 13px;");

        VBox buttons = new VBox(12);
        buttons.setAlignment(Pos.CENTER);
        buttons.setMaxWidth(360);
        boolean first = true;
        for (TerminalOption terminal : terminals) {
            String label = terminal.sequenceNo() != null
                    ? terminal.name() + "   ·   #" + String.format("%03d", terminal.sequenceNo())
                    : terminal.name();
            Button button = new Button(label);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setPrefHeight(56);
            button.setStyle("-fx-background-color: #4f8cff; -fx-text-fill: white; -fx-font-size: 16px; "
                    + "-fx-font-weight: bold; -fx-background-radius: 8;");
            if (first) {
                button.getStyleClass().add("focus-first");
                first = false;
            }
            button.setOnAction(e -> selectTerminal(terminal.id(), terminal.name()));
            buttons.getChildren().add(button);
        }

        Button changeBranch = new Button("Not this branch? Change branch code");
        changeBranch.setStyle("-fx-background-color: transparent; -fx-text-fill: #4f8cff; -fx-font-size: 12px; -fx-underline: true;");
        changeBranch.setOnAction(e -> showBranchCodeStep(null));

        VBox box = new VBox(16, title, subtitle, buttons, changeBranch);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(380);
        box.setPadding(new Insets(20, 0, 0, 0));
        showStep(box);
    }

    /** Called whether the terminal was picked from the list or auto-selected (branch has exactly
     * one) - remembers the choice immediately, independent of whether the login that follows
     * actually succeeds, since this is a per-terminal device setting, not a login credential. */
    private void selectTerminal(UUID id, String name) {
        terminalId = id;
        terminalName = name;
        PosIdentityStore.remember(branchId, branchName, terminalId, terminalName);
        showUserCodeStep();
    }

    // ---- step 3: user code + PIN ----

    private void showUserCodeStep() {
        Label title = new Label("Sign In");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setStyle("-fx-text-fill: #1b1f27;");
        String context = (branchName == null ? "" : branchName) + (terminalName == null ? "" : "  ·  " + terminalName);
        Label subtitle = new Label(context.isBlank() ? "Enter your user code and PIN." : context);
        subtitle.setWrapText(true);
        subtitle.setStyle("-fx-text-fill: #8a95a6; -fx-font-size: 13px;");

        TextField userCodeField = new TextField();
        userCodeField.setPromptText("User code (e.g. CASH001)");
        userCodeField.getStyleClass().add("focus-first");

        PasswordField pin = new PasswordField();
        pin.setPromptText("PIN");
        pin.setStyle("-fx-font-size: 22px; -fx-alignment: center;");
        pin.setMaxWidth(220);

        Label error = new Label();
        error.setStyle("-fx-text-fill: #e74c3c; -fx-font-size: 13px;");
        error.setWrapText(true);
        error.setMaxWidth(320);

        Button loginButton = LoginUiKit.bigButton("Clock In");
        loginButton.setDefaultButton(true);

        HBox pinField = LoginUiKit.fieldWithIcon("🔢", pin);
        pinField.setPrefHeight(56);
        pinField.setAlignment(Pos.CENTER_LEFT);

        GridPane keypad = LoginUiKit.buildNumericKeypad(pin);

        loginButton.setOnAction(e -> submitLogin(userCodeField, pin, error, loginButton));
        userCodeField.setOnAction(e -> pin.requestFocus());

        Button changeTerminal = new Button("Not your terminal? Change branch/terminal");
        changeTerminal.setStyle("-fx-background-color: transparent; -fx-text-fill: #4f8cff; -fx-font-size: 12px; -fx-underline: true;");
        changeTerminal.setOnAction(e -> {
            PosIdentityStore.clear();
            branchId = null;
            branchName = null;
            terminalId = null;
            terminalName = null;
            showBranchCodeStep(null);
        });

        VBox box = new VBox(14, title, subtitle, LoginUiKit.fieldWithIcon("👤", userCodeField), pinField, keypad,
                error, loginButton, changeTerminal);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(340);
        box.setPadding(new Insets(20, 0, 0, 0));
        showStep(box);
        Platform.runLater(userCodeField::requestFocus);
    }

    private void submitLogin(TextField userCodeField, PasswordField pin, Label error, Button triggerButton) {
        String userCode = userCodeField.getText() == null ? "" : userCodeField.getText().trim();
        if (userCode.isBlank()) {
            error.setText("Enter your user code.");
            return;
        }
        error.setText("");
        triggerButton.setDisable(true);
        LoginPayload payload = LoginPayload.posLogin(userCode, pin.getText(), branchId, terminalId, deviceName());
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.post("/api/auth/login", payload);
                LoginResult result = apiClient.convert(data, LoginResult.class);
                SessionStore.get().establish(result.token(), result.userId(), result.username(),
                        result.displayName(), result.role(), result.permissions(), result.branches());
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (ApiException ex) {
                Platform.runLater(() -> {
                    error.setText(ex.getMessage());
                    triggerButton.setDisable(false);
                    pin.clear();
                });
            }
        }, "chefpay-pos-login");
        worker.setDaemon(true);
        worker.start();
    }

    private static String deviceName() {
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
}
