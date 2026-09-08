package com.chefpay.plugin.api.dto;

/** Minimal customer info available at bill-close time (walk-in customers may have no phone at all). */
public record CustomerDto(
        String name,
        String phone
) {
}
