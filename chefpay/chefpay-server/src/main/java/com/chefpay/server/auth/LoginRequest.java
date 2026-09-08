package com.chefpay.server.auth;

import java.util.UUID;

/**
 * Three login shapes, tried in this order: (1) {@code username + password} - Manager/Admin, always
 * issues a {@code PASSWORD}-method JWT; (2) {@code userCode + pin} (Phase 2, item 10's fix - the
 * client's branch -> terminal -> user-code -> PIN flow) - deterministic, issues a {@code PIN}-method
 * JWT; (3) {@code pin} alone with no {@code userCode} - the pre-Phase-2 ambiguous path, kept only
 * for a not-yet-upgraded client and deliberately NOT the one any Phase 2 client ever sends.
 * deviceName/deviceType register or refresh the calling terminal.
 *
 * <p>Round 17: {@code terminalCode}, if the calling client already completed the one-time
 * Terminal Setup flow and persisted a code locally, re-identifies the SAME terminal/Device row
 * across logins/browser restarts instead of matching by {@code deviceName} (which a user could
 * rename). {@code branchId} assigns/reassigns which branch this terminal is registered at - sent
 * once from the Terminal Setup screen (or whenever an admin reassigns a terminal from the
 * Branches & Terminals page); null leaves a previously-set branch unchanged.
 *
 * <p>Phase 2: {@code userCode} is the new deterministic login identifier (see {@code
 * AppUser#getUserCode()}). {@code terminalId}, if supplied, identifies the exact {@code Device}
 * row selected on the client's terminal-select screen directly by id - preferred over the
 * terminalCode/deviceName heuristics above whenever the client already knows it (i.e. every Phase 2
 * client after its first-run branch/terminal selection); those older heuristics remain the fallback
 * for a client that hasn't gone through that flow yet (an old JavaFX install mid-upgrade, etc.).
 */
public record LoginRequest(
        String username,
        String password,
        String pin,
        String userCode,
        String deviceName,
        String deviceType,
        String terminalCode,
        UUID branchId,
        UUID terminalId
) {
}
