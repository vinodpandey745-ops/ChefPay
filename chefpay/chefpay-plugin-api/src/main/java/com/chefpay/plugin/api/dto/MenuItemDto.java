package com.chefpay.plugin.api.dto;

import java.math.BigDecimal;

public record MenuItemDto(
        String name,
        String category,
        BigDecimal price,
        String unit,
        boolean available
) {
}
