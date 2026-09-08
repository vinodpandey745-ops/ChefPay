package com.chefpay.javafx;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.SessionStore;
import com.chefpay.javafx.client.StompWebSocketClient;
import com.chefpay.javafx.client.SyncEngine;
import com.chefpay.javafx.client.dto.LoginResult;
import com.chefpay.javafx.common.AppLogo;
import com.chefpay.javafx.login.BranchSelectionView;
import com.chefpay.javafx.login.LoginView;
import com.chefpay.javafx.shell.ShellView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;
import java.util.UUID;

/** ChefPay cashier POS desktop entry point (the "Cashier PC -> JavaFX" client from the deployment diagram). */
public class ChefPayDesktopApp extends Application {

    private final ApiClient apiClient = new ApiClient();

    /** One instance per logged-in session, not one for the process's whole lifetime - see
     * {@link #showLogin} for why a fresh instance is built on every login rather than reusing a
     * single long-lived field. */
    private StompWebSocketClient wsClient;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Bistrodesk POS");
        if (AppLogo.isAvailable()) {
            stage.getIcons().add(AppLogo.image());
        }
        showLogin(stage);
        stage.setWidth(1200);
        stage.setHeight(800);
        stage.show();
    }

    private void showLogin(Stage stage) {
        LoginView loginView = new LoginView(result -> {
            // A fresh client per login, deliberately - StompWebSocketClient#stop() shuts its
            // reconnect scheduler down for good (an ExecutorService can't be restarted once shut
            // down), so an instance that's ever been logged out of can never reconnect if reused.
            // A fresh instance also starts with an empty per-topic listener map, so the previous
            // session's ShellView/TableMatrixView/etc. subscriptions (now orphaned) don't keep
            // firing alongside the new screens' identical subscriptions after a logout/login cycle.
            wsClient = new StompWebSocketClient();
            wsClient.start();
            // Round 11: (re)start the offline-cache sync engine on every login - stop() below
            // undoes this on logout so a stale session's probe never keeps running underneath a
            // fresh login's own start().
            SyncEngine.get().start(apiClient);
            resolveBranchThenShowShell(stage, result);
        });
        Scene scene = new Scene(loginView.view(), 1200, 800);
        // Round 12 §1 - app-wide touch-friendly baseline (bigger tap targets on every button/field/
        // list row that doesn't already set its own size), applied once here since every other
        // screen swaps this same Scene's root rather than creating a new Scene - see touch.css's
        // own javadoc for exactly why a stylesheet is safe alongside this app's existing inline-style
        // convention.
        var touchCss = getClass().getResource("/css/touch.css");
        if (touchCss != null) {
            scene.getStylesheets().add(touchCss.toExternalForm());
        }
        stage.setScene(scene);
    }

    /**
     * Round 12 §3: decides whether this login needs the branch-selection screen at all. A
     * single-branch restaurant (or a user restricted to exactly one branch) skips it entirely -
     * {@code LoginResponse.branches} is already this user's effective, permission-filtered list
     * (see its javadoc). Otherwise: an explicit {@code defaultBranchId} wins, then this terminal's
     * previously-remembered branch if it's still one this user is authorized for (the Round 11
     * per-terminal convenience, now re-validated against real per-user access rather than blindly
     * trusted), and only if neither resolves does the user actually have to pick.
     */
    private void resolveBranchThenShowShell(Stage stage, LoginResult result) {
        List<LoginResult.BranchSummary> branches = result.branches();
        UUID chosen = null;
        if (branches.size() == 1) {
            chosen = branches.get(0).id();
        } else if (result.defaultBranchId() != null
                && branches.stream().anyMatch(b -> b.id().equals(result.defaultBranchId()))) {
            chosen = result.defaultBranchId();
        } else {
            UUID remembered = SessionStore.get().getCurrentBranchId();
            if (remembered != null && branches.stream().anyMatch(b -> b.id().equals(remembered))) {
                chosen = remembered;
            }
        }

        if (chosen != null || branches.size() <= 1) {
            SessionStore.get().currentBranchIdProperty().set(chosen);
            showShell(stage);
            return;
        }

        BranchSelectionView branchView = new BranchSelectionView(branches, selectedId -> {
            SessionStore.get().currentBranchIdProperty().set(selectedId);
            showShell(stage);
        });
        stage.getScene().setRoot(branchView.view());
    }

    private void showShell(Stage stage) {
        ShellView shellView = new ShellView(apiClient, wsClient, () -> logout(stage));
        stage.getScene().setRoot(shellView.view());
    }

    /** Client-side only - this backend issues stateless bearer JWTs with no server-side session/
     * blacklist (see LoginView's "Keep me logged in" javadoc), so logging out just means forgetting
     * the token locally and dropping the live WebSocket connection before returning to Login. */
    private void logout(Stage stage) {
        if (wsClient != null) {
            wsClient.stop();
            wsClient = null;
        }
        SyncEngine.get().stop();
        SessionStore.get().clear();
        showLogin(stage);
    }

    @Override
    public void stop() {
        if (wsClient != null) {
            wsClient.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
