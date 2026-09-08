package com.chefpay.server.areas;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class AreaDtos {

    private AreaDtos() {
    }

    public record AreaDto(UUID id, UUID branchId, String name, int displayOrder, boolean active, long version) {
    }

    public record CreateAreaRequest(UUID branchId, @NotBlank String name, int displayOrder) {
    }

    /** Any null field (except version) leaves that attribute unchanged. */
    public record UpdateAreaRequest(String name, Integer displayOrder, Boolean active, long version) {
    }
}
