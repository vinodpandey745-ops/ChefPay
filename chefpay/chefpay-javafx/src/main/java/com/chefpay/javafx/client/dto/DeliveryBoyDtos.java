package com.chefpay.javafx.client.dto;

import java.util.UUID;

/** Mirrors {@code com.chefpay.server.delivery.DeliveryBoyDtos} (Round 9 Delivery Boy roster). */
public final class DeliveryBoyDtos {

    private DeliveryBoyDtos() {
    }

    public record DeliveryBoyDto(UUID id, String name, String phone, boolean active, long version) {
    }

    public record CreateDeliveryBoyRequest(String name, String phone) {
    }

    public record UpdateDeliveryBoyRequest(String name, String phone, boolean active, long version) {
    }
}
