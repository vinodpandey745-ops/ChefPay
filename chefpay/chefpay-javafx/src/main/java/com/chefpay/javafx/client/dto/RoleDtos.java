package com.chefpay.javafx.client.dto;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors {@code com.chefpay.server.users.RoleDto}/{@code UpdateRolePermissionsRequest} - read-side
 * copy for populating role-name dropdowns (User Management) and the Role & Permission management
 * screen's checklist editor (Round 9).
 */
public final class RoleDtos {

    private RoleDtos() {
    }

    public record RoleDto(UUID id, String name, String description, List<String> permissions, long version) {
    }

    public record UpdateRolePermissionsRequest(List<String> permissionCodes, long version) {
    }
}
