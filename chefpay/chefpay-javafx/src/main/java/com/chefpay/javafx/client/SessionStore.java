package com.chefpay.javafx.client;

import com.chefpay.javafx.client.dto.LoginResult;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.List;
import java.util.UUID;

/** Holds the current terminal's logged-in session (JWT + user info) in memory for this process's lifetime. */
public class SessionStore {

    private static final SessionStore INSTANCE = new SessionStore();

    public static SessionStore get() {
        return INSTANCE;
    }

    private String token;
    private UUID userId;
    private final StringProperty username = new SimpleStringProperty();
    private final StringProperty displayName = new SimpleStringProperty();
    private final StringProperty role = new SimpleStringProperty();
    private List<String> permissions = List.of();
    /** Round 11: which branch of a restaurant CHAIN this terminal is currently working against -
     * null means "no filter" (today's behavior: every branch's tables/orders shown together),
     * which is also the correct default for the common single-branch restaurant. Set by {@code
     * ShellView}'s branch switcher (only shown at all once a restaurant actually has 2+ branches),
     * and read by {@code TableMatrixView} to scope its {@code GET /api/tables}/{@code
     * GET /api/orders} calls. Deliberately NOT part of {@link #establish}/{@link #clear} - it's a
     * per-terminal UI preference, not part of the login session itself, so switching branches
     * doesn't require re-authenticating and it isn't wiped on logout either (the next login at the
     * same terminal keeps whatever branch was last picked there). */
    private final ObjectProperty<UUID> currentBranchId = new SimpleObjectProperty<>();
    /** Round 12 §3: this user's own permission-filtered branch list, exactly as {@code
     * LoginResponse#effectiveBranches} computed it server-side - an empty list here (rather than
     * "every branch") would mean something has gone wrong, since {@code AuthController} always
     * resolves to at least the restaurant's own branches for an unrestricted user. Set once at
     * {@link #establish}, alongside the rest of the login session (unlike {@link #currentBranchId},
     * which is a per-terminal preference and deliberately NOT reset by {@link #clear} - this list
     * IS part of the login session and must be cleared with it, since a different user logging in
     * on the same terminal may be allowed a completely different set of branches). Read by {@code
     * ShellView}'s branch switcher so it only ever offers branches this user is actually allowed to
     * work at, rather than every branch on the whole restaurant. */
    private List<LoginResult.BranchSummary> effectiveBranches = List.of();

    private SessionStore() {
    }

    public void establish(String token, UUID userId, String username, String displayName, String role,
                           List<String> permissions, List<LoginResult.BranchSummary> effectiveBranches) {
        this.token = token;
        this.userId = userId;
        this.username.set(username);
        this.displayName.set(displayName);
        this.role.set(role);
        this.permissions = permissions;
        this.effectiveBranches = effectiveBranches == null ? List.of() : effectiveBranches;
    }

    public void clear() {
        this.token = null;
        this.userId = null;
        this.username.set(null);
        this.displayName.set(null);
        this.role.set(null);
        this.permissions = List.of();
        this.effectiveBranches = List.of();
    }

    public List<LoginResult.BranchSummary> getEffectiveBranches() {
        return effectiveBranches;
    }

    public ObjectProperty<UUID> currentBranchIdProperty() {
        return currentBranchId;
    }

    public UUID getCurrentBranchId() {
        return currentBranchId.get();
    }

    public boolean isLoggedIn() {
        return token != null;
    }

    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }

    public String getToken() {
        return token;
    }

    public UUID getUserId() {
        return userId;
    }

    public StringProperty usernameProperty() {
        return username;
    }

    public StringProperty displayNameProperty() {
        return displayName;
    }

    public StringProperty roleProperty() {
        return role;
    }
}
