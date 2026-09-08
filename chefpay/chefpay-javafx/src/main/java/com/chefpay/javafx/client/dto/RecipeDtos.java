package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.recipe.RecipeDtos} - Round 14 (F2.1). */
public final class RecipeDtos {

    private RecipeDtos() {
    }

    public record RecipeLineInput(UUID inventoryItemId, BigDecimal quantityPerBatch) {
    }

    public record SaveRecipeRequest(int servingsPerBatch, String notes, List<RecipeLineInput> lines) {
    }

    public record RecipeLineDto(UUID id, UUID inventoryItemId, String inventoryItemName, String unit,
                                 BigDecimal quantityPerBatch) {
    }

    public record RecipeDto(UUID id, UUID menuItemId, String menuItemName, int servingsPerBatch, String notes,
                             boolean active, List<RecipeLineDto> lines, BigDecimal costPerServing, long version) {
    }
}
