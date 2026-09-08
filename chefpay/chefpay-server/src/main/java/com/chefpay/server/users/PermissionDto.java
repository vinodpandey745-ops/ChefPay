package com.chefpay.server.users;

import java.util.UUID;

public record PermissionDto(UUID id, String code, String description) {
}
