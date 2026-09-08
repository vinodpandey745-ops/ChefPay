package com.chefpay.javafx.client.dto;

import java.util.UUID;

/** Mirrors chefpay-server's {@code LoginRequest} wire shape (see its javadoc for the three
 * credential shapes tried in order server-side).
 *
 * <p>Phase 2: {@code userCode}/{@code branchId}/{@code terminalId} back the new POS first-run flow
 * (branch code -> terminal select -> user code + PIN, {@link com.chefpay.javafx.login.PosLoginFlowView}).
 * {@code terminalId} is the exact {@code Device} row id chosen on the terminal-select screen -
 * preferred over the older {@code terminalCode}/{@code deviceName} heuristics, so it's the only one
 * of the two this client ever sends once it knows a real id. */
public record LoginPayload(String username, String password, String pin, String userCode,
                            String deviceName, String deviceType, UUID branchId, UUID terminalId) {

    public static LoginPayload passwordLogin(String username, String password, String deviceName) {
        return new LoginPayload(username, password, null, null, deviceName, "JAVAFX_POS", null, null);
    }

    public static LoginPayload pinLogin(String pin, String deviceName) {
        return new LoginPayload(null, null, pin, null, deviceName, "JAVAFX_POS", null, null);
    }

    /** Phase 2's deterministic POS login: {@code userCode} resolves to exactly one candidate
     * account before the PIN is even checked (see {@code AuthController}'s javadoc), and
     * {@code branchId}/{@code terminalId} are the exact ids the client's own first-run flow
     * resolved, not a code/name the server has to re-match heuristically. */
    public static LoginPayload posLogin(String userCode, String pin, UUID branchId, UUID terminalId, String deviceName) {
        return new LoginPayload(null, null, pin, userCode, deviceName, "JAVAFX_POS", branchId, terminalId);
    }
}
