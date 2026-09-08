package com.chefpay.server.ai;

import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.MenuItemRepository;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Feature E - AI-written menu item descriptions. Purely a "suggest" endpoint: it never writes to
 * the {@link MenuItem} itself (that still goes through {@code MenuController}'s existing
 * {@code PATCH /api/menu/items/{id}/description}, a click away in {@code MenuManagementView}'s edit
 * dialog) - same reviewable-draft posture as Feature A's menu import.
 *
 * <p>Bistrodesk Phase 4 (requirement #24): requires the {@code AI_FEATURES} plan feature - see
 * {@link RequiresFeature}'s javadoc.
 */
@RestController
@RequestMapping("/api/ai/menu")
@RequiredArgsConstructor
@RequiresFeature("AI_FEATURES")
public class AiMenuDescriptionController {

    private static final String SYSTEM_PROMPT = """
            You write short, appetizing menu descriptions for a restaurant's printed/digital menu. Given
            an item's name, category, price, and veg/egg/non-veg classification, write ONE description
            (1-2 sentences, under 30 words) that would make a diner want to order it. Do not invent
            specific ingredients you weren't told about beyond what's obviously implied by the dish name.
            Respond with ONLY the description text - no quotes, no labels, no extra commentary.
            """;

    private final AiService aiService;
    private final MenuItemRepository itemRepository;

    @PostMapping("/{itemId}/suggest-description")
    @PreAuthorize("hasAuthority('MENU_MANAGE') and hasAuthority('AI_USE')")
    public ApiResponse<AiMenuDescriptionDtos.SuggestDescriptionResponse> suggestDescription(@PathVariable UUID itemId) {
        Restaurant restaurant = aiService.loadRestaurant();
        aiService.assertEnabled(restaurant, restaurant.isAiMenuDescriptionsEnabled(), "AI-written menu item descriptions");

        MenuItem item = itemRepository.findById(itemId).orElseThrow(() -> ApiException.notFound("Menu item not found"));
        String userPrompt = "Name: " + item.getName()
                + "\nCategory: " + item.getCategory().getName()
                + "\nPrice: " + item.getPrice()
                + "\nFood type: " + item.getFoodType().name()
                + (item.getDescription() == null || item.getDescription().isBlank() ? ""
                        : "\nExisting description (improve or replace it): " + item.getDescription());

        String suggestion = aiService.chatText(restaurant, SYSTEM_PROMPT, userPrompt).trim();
        return ApiResponse.ok(new AiMenuDescriptionDtos.SuggestDescriptionResponse(suggestion));
    }
}
