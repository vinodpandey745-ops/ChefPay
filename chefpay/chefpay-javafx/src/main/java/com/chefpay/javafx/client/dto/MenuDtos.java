package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class MenuDtos {

    private MenuDtos() {
    }

    public record CategoryDto(UUID id, String name, int displayOrder, boolean active, long version, List<ItemDto> items) {
    }

    public record ItemDto(UUID id, UUID categoryId, String name, String sku, String plu, String description,
                           BigDecimal price, String taxCode, UUID stationId, String stationName, boolean vegetarian,
                           String foodType, boolean available, boolean active, boolean directSale, BigDecimal halfPrice,
                           long version) {
    }

    public record CreateCategoryRequest(String name, int displayOrder) {
    }

    /** {@code foodType} is "VEG" / "EGG" / "NON_VEG" (see {@code com.chefpay.core.domain.FoodType} on the
     * server) - null leaves it to the server's default of VEG, mirroring how {@code vegetarian} is kept
     * alongside it. */
    public record CreateItemRequest(UUID categoryId, String name, String sku, String plu, String description,
                                     BigDecimal price, String taxCode, UUID stationId, boolean vegetarian,
                                     String foodType, boolean directSale, BigDecimal halfPrice) {
    }

    /** Any null field (except version/clearStation) leaves that attribute unchanged - mirrors the server DTO. */
    public record UpdateItemRequest(String name, BigDecimal price, String taxCode, UUID stationId,
                                     boolean clearStation, Boolean vegetarian, String foodType, Boolean available,
                                     Boolean active, Boolean directSale, BigDecimal halfPrice, long version) {
    }

    /** Round 10 - dedicated description update (AI-suggested or typed manually), see the server
     * record's javadoc for why this isn't just another field on {@link UpdateItemRequest}. */
    public record UpdateDescriptionRequest(String description, long version) {
    }
}
