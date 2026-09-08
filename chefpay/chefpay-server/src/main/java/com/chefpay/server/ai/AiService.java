package com.chefpay.server.ai;

import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Central gate + dispatch point for every AI-backed feature (Round 10, "bring your own key,
 * multi-provider"). This is the ONLY place in the server that reads {@code Restaurant.aiApiKey}
 * back out and hands it to {@link AiClient} - every feature-specific controller/service calls
 * {@link #assertEnabled} first, which enforces the same three gates for all six features: the
 * master {@code aiFeaturesEnabled} switch AND a non-blank API key AND that specific feature's own
 * switch must all be on, or the request is rejected with a client-facing 400 before anything is
 * ever sent to a third party.
 */
@Service
@RequiredArgsConstructor
public class AiService {

    private final RestaurantRepository restaurantRepository;
    private final AiClient aiClient;

    /** Single-tenant lookup, same pattern as {@code RestaurantController}/{@code NotificationService}. */
    public Restaurant loadRestaurant() {
        return restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
    }

    /**
     * Throws a 400 {@code ApiException} (never a 500) the moment any of the three gates isn't met,
     * so every AI endpoint fails the same predictable, user-actionable way ("turn this on in
     * Settings") instead of a confusing provider error.
     */
    public void assertEnabled(Restaurant restaurant, boolean featureFlag, String featureName) {
        if (!restaurant.isAiFeaturesEnabled()) {
            throw ApiException.badRequest("AI_NOT_CONFIGURED", "AI Features is turned off in Settings.");
        }
        if (restaurant.getAiApiKey() == null || restaurant.getAiApiKey().isBlank()) {
            throw ApiException.badRequest("AI_NOT_CONFIGURED", "No AI API key is configured in Settings.");
        }
        if (AiProvider.fromCode(restaurant.getAiProvider()) == null) {
            throw ApiException.badRequest("AI_NOT_CONFIGURED", "No AI provider is selected in Settings.");
        }
        if (!featureFlag) {
            throw ApiException.badRequest("AI_FEATURE_DISABLED", featureName + " is turned off in Settings.");
        }
    }

    public String chatText(Restaurant restaurant, String systemPrompt, String userPrompt) {
        return call(() -> aiClient.chatText(AiProvider.fromCode(restaurant.getAiProvider()), restaurant.getAiApiKey(),
                restaurant.getAiModel(), systemPrompt, userPrompt));
    }

    public String chatWithImage(Restaurant restaurant, String systemPrompt, String userPrompt, String base64Image, String mimeType) {
        return call(() -> aiClient.chatWithImage(AiProvider.fromCode(restaurant.getAiProvider()), restaurant.getAiApiKey(),
                restaurant.getAiModel(), systemPrompt, userPrompt, base64Image, mimeType));
    }

    /**
     * Strips a ```json ... ``` (or plain ``` ... ```) fence some models wrap structured output in,
     * so callers that expect raw JSON (the menu-import feature) can parse the response directly
     * without brittle string-search hacks scattered across each feature's own code.
     */
    public static String stripCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence != -1) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

    private String call(Supplier<String> action) {
        try {
            return action.get();
        } catch (AiException ex) {
            throw ApiException.badRequest("AI_PROVIDER_ERROR", ex.getMessage());
        }
    }
}
