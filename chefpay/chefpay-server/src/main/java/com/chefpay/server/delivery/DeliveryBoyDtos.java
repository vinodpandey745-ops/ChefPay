package com.chefpay.server.delivery;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class DeliveryBoyDtos {

    private DeliveryBoyDtos() {
    }

    public record DeliveryBoyDto(UUID id, String name, String phone, boolean active, long version) {
    }

    public record CreateDeliveryBoyRequest(@NotBlank String name, String phone) {
    }

    /** Any null field (except version) leaves that attribute unchanged. */
    public record UpdateDeliveryBoyRequest(String name, String phone, Boolean active, long version) {
    }
}
