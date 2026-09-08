package com.chefpay.server.users;

import java.util.List;
import java.util.UUID;

public record RoleDto(UUID id, String name, String description, List<String> permissions, long version) {
}
