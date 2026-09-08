package com.chefpay.server.branches;

import java.util.UUID;

/** Bistrodesk Phase 5: the branch-switcher row shape for {@code GET /api/branches/accessible} -
 * deliberately carries nothing beyond {@code id}/{@code name}, unlike the full {@link BranchDto}
 * (address/phone/terminalCount/version) the Branches management screen needs. Every authenticated
 * user may call this endpoint regardless of role/permissions (unlike {@code GET /api/branches},
 * gated on {@code BRANCH_MANAGE}/{@code USER_MANAGE}/{@code TERMINAL_MANAGE}) since knowing the
 * name of a branch you're already allowed to see data for is not itself sensitive - the list is
 * already pre-filtered to exactly the branches this caller may access. */
public record AccessibleBranchDto(UUID id, String name) {
}
