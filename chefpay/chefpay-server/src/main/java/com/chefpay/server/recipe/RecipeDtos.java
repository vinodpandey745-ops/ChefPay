package com.chefpay.server.recipe;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class RecipeDtos {

    private RecipeDtos() {
    }

    public record RecipeLineInput(@NotNull UUID inventoryItemId, @NotNull BigDecimal quantityPerBatch) {
    }

    public record SaveRecipeRequest(@Positive int servingsPerBatch, String notes,
                                     @NotEmpty List<@Valid RecipeLineInput> lines) {
    }

    public record RecipeLineDto(UUID id, UUID inventoryItemId, String inventoryItemName, String unit,
                                 BigDecimal quantityPerBatch) {
    }

    public record RecipeDto(UUID id, UUID menuItemId, String menuItemName, int servingsPerBatch, String notes,
                             boolean active, List<RecipeLineDto> lines, BigDecimal costPerServing, long version) {
    }
}
