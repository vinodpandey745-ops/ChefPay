package com.chefpay.server.terminals;

import java.time.LocalDateTime;
import java.util.UUID;

/** Round 17: the "Terminal" the web app's Branches & Terminals screen manages is really the
 * existing {@code Device} entity (see its javadoc) - this DTO just shapes it for that screen and
 * the login response, adding the branch/lastUser display fields a raw Device row doesn't carry. */
public record TerminalDto(
        UUID id,
        String name,
        String terminalCode,
        String type,
        UUID branchId,
        String branchName,
        boolean active,
        String lastUserDisplayName,
        LocalDateTime lastSeenAt,
        long version,
        // Phase 2: this terminal's human-facing per-branch sequence number ("001", "002"...) - see
        // Device#sequenceNo's javadoc. Null for a terminal registered before Phase 2 or with no
        // branch assigned.
        Integer sequenceNo
) {
}
