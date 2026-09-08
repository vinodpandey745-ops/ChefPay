package com.chefpay.plugin.api;

public record RestaurantInfo(
        String name,
        String address,
        String phone,
        String gstin,
        String currencySymbol
) {
}
