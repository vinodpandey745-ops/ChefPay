package com.chefpay.server.kitchen;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class KitchenDtos {

    private KitchenDtos() {
    }

    public record StationDto(UUID id, String name, int displayOrder, boolean active, long version) {
    }

    public record CreateStationRequest(@NotBlank String name, int displayOrder) {
    }

    /** Any null field (except version) leaves that attribute unchanged. */
    public record UpdateStationRequest(String name, Integer displayOrder, Boolean active, long version) {
    }
}
