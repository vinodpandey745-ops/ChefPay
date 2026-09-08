package com.chefpay.server.users;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Round 12: {@code branchIds}/{@code branchNames} are this user's explicit branch assignment
 * (empty = unrestricted - see {@code AppUser.branches}' javadoc), {@code defaultBranchId} is the
 * branch that skips the post-login selection screen for them. {@code createdAt} (Round 15) is
 * {@code BaseEntity}'s existing audit timestamp, just not previously mapped onto this DTO - used by
 * chefpay-web's Staff Directory as the "Registered Date" column. */
public record UserDto(
        UUID id,
        String username,
        String displayName,
        String role,
        boolean active,
        boolean hasPin,
        List<UUID> branchIds,
        List<String> branchNames,
        UUID defaultBranchId,
        LocalDateTime createdAt,
        long version,
        // Phase 2: the deterministic login identifier (see AppUser#userCode's javadoc) and
        // per-terminal access restriction (see AppUser#terminals's javadoc, empty = unrestricted -
        // same convention as branchIds/branchNames above).
        String userCode,
        List<UUID> terminalIds,
        List<String> terminalNames
) {
}
