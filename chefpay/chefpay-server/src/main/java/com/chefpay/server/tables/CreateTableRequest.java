package com.chefpay.server.tables;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreateTableRequest(
        @NotNull UUID floorId,
        @NotBlank String name,
        @Positive int seatingCapacity,
        String section,
        Integer gridRow,
        Integer gridColumn
) {
}
