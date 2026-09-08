package com.chefpay.javafx.login;

import java.util.UUID;
import java.util.prefs.Preferences;

/**
 * Phase 2 POS first-run flow (PHASE2_ORG_SUBSCRIPTION_DESIGN.md Section E): remembers which
 * branch/terminal this physical machine is registered as, via {@link Preferences} - the same
 * local-persistence mechanism {@link LoginView} already uses for the remembered username (see its
 * "Keep me logged in" javadoc), rather than inventing a second one.
 *
 * <p>Deliberately does NOT remember the user code or PIN - only PIN entry is meant to repeat on a
 * normal restart; branch code + terminal selection is what's meant to be skipped, until the user
 * explicitly clears it via the "Not your terminal?" link on {@link PosLoginFlowView}'s user-code
 * step.
 */
final class PosIdentityStore {

    private static final Preferences PREFS = Preferences.userNodeForPackage(PosIdentityStore.class);
    private static final String KEY_BRANCH_ID = "posBranchId";
    private static final String KEY_BRANCH_NAME = "posBranchName";
    private static final String KEY_TERMINAL_ID = "posTerminalId";
    private static final String KEY_TERMINAL_NAME = "posTerminalName";

    private PosIdentityStore() {
    }

    static UUID getBranchId() {
        return parseUuid(PREFS.get(KEY_BRANCH_ID, null));
    }

    static String getBranchName() {
        return PREFS.get(KEY_BRANCH_NAME, null);
    }

    static UUID getTerminalId() {
        return parseUuid(PREFS.get(KEY_TERMINAL_ID, null));
    }

    static String getTerminalName() {
        return PREFS.get(KEY_TERMINAL_NAME, null);
    }

    /** Called as soon as a terminal is determined (picked, or auto-selected because a branch has
     * exactly one) - independent of whether the login attempt that follows actually succeeds, since
     * this is a per-terminal device setting, not a login credential. */
    static void remember(UUID branchId, String branchName, UUID terminalId, String terminalName) {
        PREFS.put(KEY_BRANCH_ID, branchId.toString());
        PREFS.put(KEY_BRANCH_NAME, branchName == null ? "" : branchName);
        PREFS.put(KEY_TERMINAL_ID, terminalId.toString());
        PREFS.put(KEY_TERMINAL_NAME, terminalName == null ? "" : terminalName);
    }

    static void clear() {
        PREFS.remove(KEY_BRANCH_ID);
        PREFS.remove(KEY_BRANCH_NAME);
        PREFS.remove(KEY_TERMINAL_ID);
        PREFS.remove(KEY_TERMINAL_NAME);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
