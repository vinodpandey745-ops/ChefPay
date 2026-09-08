package com.chefpay.server.auth;

import java.util.List;
import java.util.UUID;

/** Round 12: {@code branches} is this user's effective branch list - their own {@code
 * AppUser.branches} assignment if non-empty, otherwise every branch on the restaurant (an
 * unassigned user is unrestricted, matching every existing single-branch install's zero
 * {@code app_user_branch} rows - see {@code AppUser.branches}' javadoc). {@code ShellView} shows a
 * branch-selection screen right after login only when this list has 2+ entries; a single-branch
 * restaurant (or a user restricted to exactly one) never sees it. {@code defaultBranchId} (if set
 * and present in {@code branches}) preselects that branch and skips the screen even then.
 *
 * <p>Round 17: {@code organizationId}/{@code organizationName} and {@code terminal} carry the new
 * identity model so the web client can show "which org / branch / terminal you're logging into"
 * right on the login screen and in the app shell afterward, per the Organization/Branch/Terminal
 * request - see {@code Restaurant}'s and {@code Device}'s Round 17 javadocs for what these fields
 * actually mean and why they live on the existing entities rather than a new multi-tenant model. */
public record LoginResponse(
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
        TerminalSummary terminal,
        String userCode,
        String loginMethod
) {
    public record BranchSummary(UUID id, String name) {
    }

    public record TerminalSummary(UUID id, String terminalCode, String name, UUID branchId, String branchName) {
    }
}
