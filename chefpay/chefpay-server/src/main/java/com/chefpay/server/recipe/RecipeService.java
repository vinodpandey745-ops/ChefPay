package com.chefpay.server.recipe;

import com.chefpay.core.domain.InventoryItem;
import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.Recipe;
import com.chefpay.core.domain.RecipeLine;
import com.chefpay.core.repository.InventoryItemRepository;
import com.chefpay.core.repository.MenuItemRepository;
import com.chefpay.core.repository.RecipeLineRepository;
import com.chefpay.core.repository.RecipeRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Round 14 (F2.1) - "Recipe-Level Real-Time Inventory Deduction." Owns the {@link Recipe}/{@link
 * RecipeLine} CRUD (a straightforward "replace the whole line list" edit, same pattern {@code
 * PurchaseOrderService#replaceItems} already uses for a PO's items) and the deterministic
 * per-serving cost calculation every later Round 14 feature (Menu Engineering Matrix, Dynamic
 * Pricing) builds on. Deduction itself - reducing {@code InventoryItem.quantityOnHand} when an
 * order is actually sent to the kitchen - lives in {@link #deductForOrderItem}, called from {@code
 * OrderService#sendToKitchen}; see that call site's comment for why send-to-kitchen (not
 * add-to-order) is the deduction moment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final RecipeLineRepository recipeLineRepository;
    private final MenuItemRepository menuItemRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final InventoryService inventoryService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Optional<Recipe> findByMenuItem(UUID menuItemId) {
        return recipeRepository.findByMenuItemId(menuItemId);
    }

    @Transactional
    public Recipe saveRecipe(UUID menuItemId, int servingsPerBatch, String notes,
                              List<RecipeDtos.RecipeLineInput> lineInputs, UUID actorUserId) {
        MenuItem menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> ApiException.notFound("Menu item not found"));
        if (lineInputs == null || lineInputs.isEmpty()) {
            throw ApiException.badRequest("RECIPE_HAS_NO_LINES", "A recipe needs at least one ingredient line.");
        }
        if (servingsPerBatch <= 0) {
            throw ApiException.badRequest("INVALID_SERVINGS", "Servings per batch must be at least 1.");
        }

        Recipe recipe = recipeRepository.findByMenuItemId(menuItemId).orElseGet(() -> Recipe.builder().menuItem(menuItem).build());
        recipe.setServingsPerBatch(servingsPerBatch);
        recipe.setNotes(notes);
        recipe.setActive(true);
        Recipe savedRecipe = recipeRepository.save(recipe);

        List<RecipeLine> existing = recipeLineRepository.findByRecipeIdOrderByCreatedAtAsc(savedRecipe.getId());
        if (!existing.isEmpty()) {
            recipeLineRepository.deleteAll(existing);
        }
        List<RecipeLine> freshLines = new ArrayList<>();
        for (RecipeDtos.RecipeLineInput input : lineInputs) {
            InventoryItem item = inventoryItemRepository.findById(input.inventoryItemId())
                    .orElseThrow(() -> ApiException.notFound("Inventory item not found"));
            if (input.quantityPerBatch() == null || input.quantityPerBatch().compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be greater than zero for " + item.getName() + ".");
            }
            freshLines.add(RecipeLine.builder().recipe(savedRecipe).inventoryItem(item).quantityPerBatch(input.quantityPerBatch()).build());
        }
        recipeLineRepository.saveAll(freshLines);
        savedRecipe.setLines(freshLines);

        auditService.record(actorUserId, null, "Recipe", savedRecipe.getId(),
                existing.isEmpty() ? "RECIPE_CREATED" : "RECIPE_MODIFIED", null, menuItem.getName(), null, CorrelationIdHolder.get());
        return savedRecipe;
    }

    @Transactional
    public void deactivate(UUID menuItemId, UUID actorUserId) {
        Recipe recipe = recipeRepository.findByMenuItemId(menuItemId)
                .orElseThrow(() -> ApiException.notFound("No recipe exists for this menu item"));
        recipe.setActive(false);
        recipeRepository.save(recipe);
        auditService.record(actorUserId, null, "Recipe", recipe.getId(), "RECIPE_DEACTIVATED", null, null, null, CorrelationIdHolder.get());
    }

    /** Cost of ONE serving, in the restaurant's currency - null (rather than zero) when any line's
     * {@code InventoryItem#getCostPerUnit()} isn't set, so a caller (Menu Engineering, Dynamic
     * Pricing) can distinguish "genuinely free ingredients" from "cost data incomplete, don't
     * classify this item yet" instead of silently understating cost as zero. */
    @Transactional(readOnly = true)
    public BigDecimal costPerServing(Recipe recipe) {
        List<RecipeLine> lines = recipeLineRepository.findByRecipeIdOrderByCreatedAtAsc(recipe.getId());
        if (lines.isEmpty()) {
            return null;
        }
        BigDecimal batchCost = BigDecimal.ZERO;
        for (RecipeLine line : lines) {
            BigDecimal unitCost = line.getInventoryItem().getCostPerUnit();
            if (unitCost == null) {
                return null;
            }
            batchCost = batchCost.add(line.getQuantityPerBatch().multiply(unitCost));
        }
        return batchCost.divide(BigDecimal.valueOf(recipe.getServingsPerBatch()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Round 14 F2.1's real-time deduction: if {@code orderItem.menuItem} has an active recipe,
     * deducts {@code line.quantityPerBatch / servingsPerBatch * orderItem.quantity} of each
     * ingredient via {@link InventoryService#recordTransaction} (type {@code DEDUCT}, same ledger
     * every other stock movement goes through). Silently does nothing when no recipe exists - see
     * this class's javadoc; NOT every {@link MenuItem} is expected to have one this round.
     *
     * <p>Deliberately swallows any single line's failure (insufficient stock, a stale {@code
     * InventoryItem} version) rather than letting it propagate - {@code OrderService#sendToKitchen}
     * calls this per pending item wrapped in its own try/catch for the same reason {@code
     * OrderService}'s existing fraud-rule-engine hook is wrapped: sending an order to the kitchen
     * must never fail because a stock ledger entry couldn't be written. A failed/partial deduction
     * is logged, not silently lost - see the log line below - so a manager can reconcile it later
     * via the ordinary Inventory Adjustment screen.
     */
    @Transactional
    public void deductForOrderItem(OrderItem orderItem, UUID actorUserId) {
        Optional<Recipe> recipeOpt = recipeRepository.findByMenuItemId(orderItem.getMenuItem().getId());
        if (recipeOpt.isEmpty() || !recipeOpt.get().isActive()) {
            return;
        }
        Recipe recipe = recipeOpt.get();
        List<RecipeLine> lines = recipeLineRepository.findByRecipeIdOrderByCreatedAtAsc(recipe.getId());
        BigDecimal servings = orderItem.getQuantity();
        for (RecipeLine recipeLine : lines) {
            try {
                BigDecimal perServing = recipeLine.getQuantityPerBatch()
                        .divide(BigDecimal.valueOf(recipe.getServingsPerBatch()), 6, RoundingMode.HALF_UP);
                BigDecimal deductQty = perServing.multiply(servings);
                if (deductQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                InventoryItem item = recipeLine.getInventoryItem();
                String reason = "Recipe deduction: " + orderItem.getMenuItem().getName() + " x" + servings.stripTrailingZeros().toPlainString();
                inventoryService.recordTransaction(item.getId(), "DEDUCT", deductQty.setScale(3, RoundingMode.HALF_UP),
                        reason, item.getVersion(), actorUserId);
            } catch (Exception ex) {
                log.error("Recipe stock deduction failed for order item {} (menu item {}), ingredient {} - "
                                + "continuing without it; reconcile manually via Inventory Adjustment if needed.",
                        orderItem.getId(), orderItem.getMenuItem().getName(), recipeLine.getInventoryItem().getName(), ex);
            }
        }
    }
}
