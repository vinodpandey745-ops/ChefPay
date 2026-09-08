package com.chefpay.server.restaurant;

import jakarta.validation.constraints.NotBlank;

public record CreateFloorRequest(@NotBlank String name, int displayOrder) {
}
