package com.chefpay.javafx.client.dto;

import java.util.UUID;

public record TableDto(
        UUID id,
        UUID floorId,
        String name,
        int seatingCapacity,
        String section,
        String status,
        Integer gridRow,
        Integer gridColumn,
        boolean active,
        long version
) {
}
