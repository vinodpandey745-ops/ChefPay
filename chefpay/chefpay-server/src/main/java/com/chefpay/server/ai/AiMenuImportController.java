package com.chefpay.server.ai;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.FoodType;
import com.chefpay.core.domain.MenuCategory;
import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.MenuCategoryRepository;
import com.chefpay.core.repository.MenuItemRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Feature A - "AI Menu Setup from Photo/PDF" (image only this round; a real PDF-to-image render
 * pipeline is a separate piece of scope). Two-step, never a one-shot import: {@link #analyze}
 * reads the photo and returns a DRAFT list the owner reviews/edits/deselects in the client, and
 * only {@link #apply} actually writes {@link MenuCategory}/{@link MenuItem} rows - matching the
 * app's existing "AI drafts, a human still presses Save" posture (see {@code SettingsView}'s AI
 * key handling and Feature E's description-suggest flow for the same pattern elsewhere).
 *
 * <p>Bistrodesk Phase 4 (requirement #24): requires the {@code AI_FEATURES} plan feature - see
 * {@link RequiresFeature}'s javadoc.
 *
 * <p>Bistrodesk branch-isolation release (requirement #1): {@link #apply} is the second of two
 * MenuItem-creating paths in this codebase (the other is {@code MenuController#createItem}) - both
 * must resolve a mandatory branch or the "shared/leaks into every branch" bug this release closes
 * would simply reopen through AI import instead. One import batch always lands on exactly one
 * branch, resolved the same way via {@link BranchAccessService#resolveEffectiveBranchId}.
 */
@RestController
@RequestMapping("/api/ai/menu-import")
@RequiredArgsConstructor
@RequiresFeature("AI_FEATURES")
public class AiMenuImportController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You read photos of restaurant menus (printed or handwritten) and extract every dish/drink
            you can clearly identify into strict JSON. Respond with ONLY a JSON array (no prose, no
            markdown fences) where each element has exactly these fields:
            {"name": string, "categoryName": string, "price": number, "foodType": "VEG"|"EGG"|"NON_VEG", "description": string}
            Rules: "categoryName" is a short section name such as "Starters", "Main Course", "Beverages",
            "Desserts" - group similar items together and reuse the same categoryName string for items in
            the same section. "price" is the numeric price only (no currency symbol) - use the full-size
            price if more than one size is shown, and omit any item whose price you genuinely cannot read.
            "foodType" is your best guess from any veg/non-veg markers, icons, or the dish name itself -
            default to "VEG" only if you have no real indication either way. "description" is a short
            (under 15 words) description if the menu already shows one, otherwise an empty string - do
            NOT invent a marketing description in this step, that is a separate feature. If you cannot
            read any items at all, respond with an empty JSON array: []
            """;

    private final AiService aiService;
    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final BranchAccessService branchAccessService;

    @PostMapping("/analyze")
    @PreAuthorize("hasAuthority('MENU_MANAGE') and hasAuthority('AI_USE')")
    public ApiResponse<AiMenuImportDtos.AnalyzeResponse> analyze(@Valid @RequestBody AiMenuImportDtos.AnalyzeRequest request) {
        Restaurant restaurant = aiService.loadRestaurant();
        aiService.assertEnabled(restaurant, restaurant.isAiMenuImportEnabled(), "AI Menu Setup from Photo/PDF");

        String raw = aiService.chatWithImage(restaurant, SYSTEM_PROMPT,
                "Extract every menu item from this photo as the JSON array described.",
                request.imageBase64(), request.mimeType() == null || request.mimeType().isBlank() ? "image/jpeg" : request.mimeType());

        List<AiMenuImportDtos.DraftItemDto> items = parseDraftItems(raw);
        return ApiResponse.ok(new AiMenuImportDtos.AnalyzeResponse(items));
    }

    @PostMapping("/apply")
    @PreAuthorize("hasAuthority('MENU_MANAGE') and hasAuthority('AI_USE')")
    public ApiResponse<AiMenuImportDtos.ApplyResponse> apply(@Valid @RequestBody AiMenuImportDtos.ApplyRequest request,
                                                              @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // Bistrodesk branch-isolation release (requirement #1): mandatory, same resolution
        // MenuController#createItem uses - never leaves an imported item branchless.
        AppUser requester = branchAccessService.resolve(principal);
        UUID resolvedBranchId = branchAccessService.resolveEffectiveBranchId(requester, request.branchId());
        Branch branch = branchRepository.findById(resolvedBranchId).orElseThrow(() -> ApiException.notFound("Branch not found"));

        int categoriesCreated = 0;
        int itemsCreated = 0;
        for (AiMenuImportDtos.ApplyItemDto draft : request.items()) {
            MenuCategory category;
            if (draft.categoryId() != null) {
                // Round 18: the client's review step already resolved this draft's category name
                // to a real, existing MenuCategory (or the user explicitly picked one) - use it
                // verbatim, no name matching/creation at all, so this path can never create a
                // near-duplicate like "Beverage" next to an existing "Beverages".
                category = categoryRepository.findById(draft.categoryId())
                        .orElseThrow(() -> ApiException.notFound("Category not found: " + draft.categoryId()));
            } else {
                // Fallback for an older client (or a group explicitly marked "create new"): exact
                // case-insensitive match, else create - same behavior this endpoint always had.
                category = categoryRepository.findByNameIgnoreCase(draft.categoryName().trim()).orElse(null);
                if (category == null) {
                    category = categoryRepository.save(MenuCategory.builder()
                            .name(draft.categoryName().trim())
                            .displayOrder(0)
                            .build());
                    categoriesCreated++;
                }
            }
            FoodType foodType = parseFoodType(draft.foodType());
            itemRepository.save(MenuItem.builder()
                    .category(category)
                    .name(draft.name().trim())
                    .description(draft.description() == null || draft.description().isBlank() ? null : draft.description().trim())
                    .price(draft.price())
                    .vegetarian(foodType == FoodType.VEG)
                    .foodType(foodType)
                    .branch(branch)
                    .build());
            itemsCreated++;
        }
        return ApiResponse.ok(new AiMenuImportDtos.ApplyResponse(itemsCreated, categoriesCreated));
    }

    private FoodType parseFoodType(String raw) {
        if (raw == null) {
            return FoodType.VEG;
        }
        try {
            return FoodType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FoodType.VEG;
        }
    }

    private List<AiMenuImportDtos.DraftItemDto> parseDraftItems(String raw) {
        String json = AiService.stripCodeFence(raw);
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                throw new IllegalArgumentException("Response was not a JSON array");
            }
            List<AiMenuImportDtos.DraftItemDto> items = new ArrayList<>();
            for (JsonNode node : root) {
                String name = node.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                String categoryName = node.path("categoryName").asText("Uncategorized");
                BigDecimal price = node.hasNonNull("price") ? new BigDecimal(node.path("price").asText("0")) : null;
                String foodType = node.path("foodType").asText("VEG");
                String description = node.path("description").asText("");
                items.add(new AiMenuImportDtos.DraftItemDto(name.trim(), categoryName.trim(), price, foodType, description));
            }
            return items;
        } catch (Exception ex) {
            throw ApiException.badRequest("AI_PARSE_ERROR",
                    "Couldn't read the AI's response as a menu list. Try a clearer photo, or a different AI model. ("
                            + ex.getMessage() + ")");
        }
    }
}
