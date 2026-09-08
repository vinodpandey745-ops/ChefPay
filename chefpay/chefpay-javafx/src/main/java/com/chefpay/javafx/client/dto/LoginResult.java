package com.chefpay.javafx.client.dto;

import java.util.List;
import java.util.UUID;

/** Mirrors chefpay-server's {@code LoginResponse} wire shape.
 *
 * <p>Round 19 hotfix: {@code organizationId}/{@code organizationName}/{@code terminal} were added
 * to the server's {@code LoginResponse} in Round 17 but never mirrored here, which on its own
 * would have been harmless (the fix that actually matters is {@link com.chefpay.javafx.client.ApiClient}'s
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = false}) - added anyway so this record is a complete, current
 * mirror of the wire shape rather than a stale pre-Round-17 snapshot, and so the JavaFX client has
 * the same organization/terminal identity available to it that the web client already shows. */
public record LoginResult(
        String token,
        UUID userId,
        String username,
        String displayName,
        String role,
        List<String> permissions,
        List<BranchSummary> branches,
        UUID defaultBranchId,
        String organizationId,
        String organizationName,
        TerminalSummary terminal
) {
    public record BranchSummary(UUID id, String name) {
    }

    public record TerminalSummary(UUID id, String terminalCode, String name, UUID branchId, String branchName) {
    }
}
