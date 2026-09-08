package com.chefpay.core.domain;

/**
 * Round 9: precise 3-way food-type classification for a {@link MenuItem}, replacing the plain
 * {@code vegetarian} boolean for anything that needs to distinguish "contains egg" from either
 * pure veg or non-veg (PetPooja-style green/yellow/red indicator on the order-taking menu tiles).
 * Order is VEG, EGG, NON_VEG - but every usage should persist/compare by name
 * ({@code @Enumerated(EnumType.STRING)}), never by ordinal, so this order is purely cosmetic.
 */
public enum FoodType {
    VEG,
    EGG,
    NON_VEG
}
