package com.chefpay.javafx.client.dto;

import java.util.UUID;

/** Mirrors {@code com.chefpay.server.users.PermissionDto} - the master permission catalog (Round 9). */
public final class PermissionDtos {

    private PermissionDtos() {
    }

    public record PermissionDto(UUID id, String code, String description) {
    }
}
