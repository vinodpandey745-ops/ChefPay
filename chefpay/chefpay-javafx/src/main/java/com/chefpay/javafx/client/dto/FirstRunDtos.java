package com.chefpay.javafx.client.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Mirrors the two new public (unauthenticated) endpoints backing the Phase 2 POS first-run flow
 * (branch code -> terminal select, see {@code com.chefpay.server.branches.BranchController} and
 * {@code PHASE2_ORG_SUBSCRIPTION_DESIGN.md} Section E). */
public final class FirstRunDtos {

    private FirstRunDtos() {
    }

    /** Mirrors {@code com.chefpay.server.branches.BranchByCodeResponse} - deliberately carries
     * nothing beyond what's needed to move on to Terminal Select. */
    public record BranchByCodeResult(UUID branchId, String branchName, boolean active) {
    }

    /** Mirrors {@code com.chefpay.server.terminals.TerminalDto}. {@code GET
     * /api/branches/{id}/terminals} only ever returns active terminals, already sorted by
     * {@code sequenceNo}. */
    public record TerminalOption(UUID id, String name, String terminalCode, String type, UUID branchId,
                                  String branchName, boolean active, String lastUserDisplayName,
                                  LocalDateTime lastSeenAt, long version, Integer sequenceNo) {
    }
}
