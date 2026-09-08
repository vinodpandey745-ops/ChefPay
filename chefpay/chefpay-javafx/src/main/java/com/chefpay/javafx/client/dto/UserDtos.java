package com.chefpay.javafx.client.dto;

import java.util.List;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.users.UserDto}/{@code CreateUserRequest}/{@code UpdateUserRequest} (Round 9 User Management screen). */
public final class UserDtos {

    private UserDtos() {
    }

    public record UserDto(UUID id, String username, String displayName, String role, boolean active, boolean hasPin,
                           List<UUID> branchIds, List<String> branchNames, UUID defaultBranchId, long version) {
    }

    public record CreateUserRequest(String username, String displayName, String password, String pin, String role) {
    }

    /** Any null field (except version) leaves that attribute unchanged. version must match the current row (optimistic locking). */
    public record UpdateUserRequest(String displayName, String role, Boolean active, String newPassword, String newPin, long version) {
    }

    /** Round 12: which branches this user may work at + their default - see server's
     * {@code UpdateUserBranchesRequest}. Empty/null branchIds = unrestricted. */
    public record UpdateUserBranchesRequest(List<UUID> branchIds, UUID defaultBranchId, long version) {
    }
}
