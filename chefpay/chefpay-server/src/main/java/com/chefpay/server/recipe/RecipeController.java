package com.chefpay.server.recipe;

import com.chefpay.core.domain.Recipe;
import com.chefpay.core.domain.RecipeLine;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Round 14 (F2.1) - recipe (bill-of-materials) CRUD, gated behind {@code MENU_MANAGE} (defining a
 * recipe is a menu-authoring action, same audience as editing the {@link
 * com.chefpay.core.domain.MenuItem} it belongs to) OR {@code INVENTORY_MANAGE} (a stock-focused
 * manager wiring up recipes to get accurate deduction, without necessarily holding full menu
 * editing rights).
 */
@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
public class RecipeController {

    private final RecipeService recipeService;

    @GetMapping("/menu-item/{menuItemId}")
    @PreAuthorize("hasAuthority('MENU_VIEW') or hasAuthority('MENU_MANAGE') or hasAuthority('INVENTORY_VIEW') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<RecipeDtos.RecipeDto> getForMenuItem(@PathVariable UUID menuItemId) {
        Recipe recipe = recipeService.findByMenuItem(menuItemId)
                .orElseThrow(() -> ApiException.notFound("No recipe defined for this menu item yet"));
        return ApiResponse.ok(toDto(recipe));
    }

    @PutMapping("/menu-item/{menuItemId}")
    @PreAuthorize("hasAuthority('MENU_MANAGE') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<RecipeDtos.RecipeDto> save(@PathVariable UUID menuItemId, @Valid @RequestBody RecipeDtos.SaveRecipeRequest request,
                                                   @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Recipe saved = recipeService.saveRecipe(menuItemId, request.servingsPerBatch(), request.notes(), request.lines(), userId(principal));
        return ApiResponse.ok(toDto(saved));
    }

    @DeleteMapping("/menu-item/{menuItemId}")
    @PreAuthorize("hasAuthority('MENU_MANAGE') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<Void> deactivate(@PathVariable UUID menuItemId, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        recipeService.deactivate(menuItemId, userId(principal));
        return ApiResponse.ok(null);
    }

    private RecipeDtos.RecipeDto toDto(Recipe recipe) {
        List<RecipeDtos.RecipeLineDto> lines = recipe.getLines().stream()
                .map(this::toLineDto)
                .toList();
        return new RecipeDtos.RecipeDto(recipe.getId(), recipe.getMenuItem().getId(), recipe.getMenuItem().getName(),
                recipe.getServingsPerBatch(), recipe.getNotes(), recipe.isActive(), lines,
                recipeService.costPerServing(recipe), recipe.getVersion());
    }

    private RecipeDtos.RecipeLineDto toLineDto(RecipeLine line) {
        return new RecipeDtos.RecipeLineDto(line.getId(), line.getInventoryItem().getId(), line.getInventoryItem().getName(),
                line.getInventoryItem().getUnit(), line.getQuantityPerBatch());
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
