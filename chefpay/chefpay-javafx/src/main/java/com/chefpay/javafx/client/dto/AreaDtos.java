package com.chefpay.javafx.client.dto;

import java.util.UUID;

/** Mirrors {@code com.chefpay.server.areas.AreaDtos} (Round 8 Area Management screen / seating-section picker). */
public final class AreaDtos {

    private AreaDtos() {
    }

    public record AreaDto(UUID id, UUID branchId, String name, int displayOrder, boolean active, long version) {
    }

    public record CreateAreaRequest(UUID branchId, String name, int displayOrder) {
    }

    /** Any null field (except version) leaves that attribute unchanged. */
    public record UpdateAreaRequest(String name, Integer displayOrder, Boolean active, long version) {
    }
}
