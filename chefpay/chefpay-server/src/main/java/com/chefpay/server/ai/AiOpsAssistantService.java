package com.chefpay.server.ai;

import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.MenuItemRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Round 14 (F4.1) - "Natural-Language Ops Assistant," the bounded WRITE half that sits alongside
 * the existing read-only "Ask Your Data" chat ({@link AiInsightsController}). Deliberately NOT a
 * general-purpose "let the AI run arbitrary actions" feature - {@link #interpret} maps free text to
 * exactly ONE allow-listed action ({@code TOGGLE_ITEM_AVAILABILITY}, the requirement's own "86 an
 * item" example) or reports it couldn't, and NEVER executes anything itself. Every write goes
 * through a separate, explicit {@link #executeToggleAvailability} call the client only offers after
 * showing the interpreted action back to the user for confirmation - the same "propose, then a
 * human confirms" shape {@code ReplenishmentService}/{@code PriceSuggestionService} already use for
 * AI/rule-driven suggestions, just synchronous instead of stored as a row to review later.
 *
 * <p>Gated behind {@code Restaurant#isNlAssistantWriteCommandsEnabled()} specifically (separate
 * from {@link Restaurant#isAiInsightsChatEnabled()} - a restaurant can run one without the other),
 * on top of the same three AI gates {@link AiService#assertEnabled} already enforces for every AI
 * feature in this codebase.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiOpsAssistantService {

    private static final List<String> ALLOWED_ACTIONS = List.of("TOGGLE_ITEM_AVAILABILITY", "UNRECOGNIZED");

    private static final String SYSTEM_PROMPT = """
            You are a restaurant back-office command interpreter. You will be given ONE plain-language
            instruction from a manager. Your ONLY job is to map it to a single JSON object, with no other
            text, no markdown fences, and no commentary:

            {"action": "TOGGLE_ITEM_AVAILABILITY", "menuItemName": "<best-guess item name from the instruction>", "available": true|false}

            Use "available": false for instructions like "86 the X", "we're out of X", "take X off the menu",
            "mark X unavailable". Use "available": true for "bring back X", "X is available again", "un-86 X".

            If the instruction does not clearly match making ONE menu item available or unavailable, respond
            EXACTLY with:
            {"action": "UNRECOGNIZED", "menuItemName": null, "available": null}

            Never invent an action outside this set. Never respond with anything except this one JSON object.
            """;

    private final AiService aiService;
    private final MenuItemRepository menuItemRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final WebSocketEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public AiOpsDtos.InterpretedCommandDto interpret(String instruction) {
        Restaurant restaurant = aiService.loadRestaurant();
        aiService.assertEnabled(restaurant, restaurant.isNlAssistantWriteCommandsEnabled(), "the NL Ops Assistant");

        String raw = aiService.chatText(restaurant, SYSTEM_PROMPT, instruction);
        JsonNode node = parseJsonSafely(raw);
        if (node == null) {
            return unrecognized("Couldn't understand that as a command - try something like \"86 the Chicken Biryani\".");
        }
        String action = node.hasNonNull("action") ? node.get("action").asText() : "UNRECOGNIZED";
        if (!ALLOWED_ACTIONS.contains(action) || !"TOGGLE_ITEM_AVAILABILITY".equals(action)) {
            return unrecognized("Couldn't map that to a supported action - only marking a menu item available/unavailable is supported today.");
        }
        if (!node.hasNonNull("menuItemName") || !node.hasNonNull("available")) {
            return unrecognized("Couldn't identify which menu item and which way to set it - try naming the item explicitly.");
        }
        String menuItemName = node.get("menuItemName").asText();
        boolean proposedAvailable = node.get("available").asBoolean();

        List<MenuItem> matches = menuItemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(mi -> mi.getName().equalsIgnoreCase(menuItemName) || mi.getName().toLowerCase().contains(menuItemName.toLowerCase()))
                .toList();
        if (matches.isEmpty()) {
            return unrecognized("No active menu item matching \"" + menuItemName + "\" was found.");
        }
        if (matches.size() > 1) {
            String names = matches.stream().map(MenuItem::getName).limit(5).reduce((a, b) -> a + ", " + b).orElse("");
            return unrecognized("More than one menu item matches \"" + menuItemName + "\" (" + names + ") - be more specific.");
        }
        MenuItem item = matches.get(0);
        String summary = (proposedAvailable ? "Mark " : "86 (mark unavailable) ") + item.getName()
                + (proposedAvailable ? " as available again." : ".");
        return new AiOpsDtos.InterpretedCommandDto("TOGGLE_ITEM_AVAILABILITY", true, item.getId(), item.getName(),
                item.isAvailable(), proposedAvailable, summary);
    }

    @Transactional
    public AiOpsDtos.ExecutedCommandDto executeToggleAvailability(UUID menuItemId, boolean available, long expectedVersion, UUID actorUserId) {
        MenuItem item = menuItemRepository.findById(menuItemId).orElseThrow(() -> ApiException.notFound("Menu item not found"));
        if (item.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(MenuItem.class, menuItemId);
        }
        boolean changed = item.isAvailable() != available;
        item.setAvailable(available);
        MenuItem saved = menuItemRepository.save(item);

        if (changed) {
            eventPublisher.publish("/topic/menu", "MENU_AVAILABILITY_CHANGED", saved.getId(), saved.getVersion(),
                    Map.of("itemName", saved.getName(), "available", saved.isAvailable()));
        }
        auditService.record(actorUserId, null, "MenuItem", saved.getId(), "AI_ASSISTANT_AVAILABILITY_TOGGLE",
                null, "available=" + available, "Via NL Ops Assistant", CorrelationIdHolder.get());
        return new AiOpsDtos.ExecutedCommandDto(true, saved.getName() + " is now " + (available ? "available" : "marked unavailable") + ".");
    }

    private AiOpsDtos.InterpretedCommandDto unrecognized(String summary) {
        return new AiOpsDtos.InterpretedCommandDto("UNRECOGNIZED", false, null, null, null, null, summary);
    }

    private JsonNode parseJsonSafely(String raw) {
        try {
            return objectMapper.readTree(AiService.stripCodeFence(raw));
        } catch (Exception ex) {
            log.debug("NL Ops Assistant: could not parse AI response as JSON: {}", raw, ex);
            return null;
        }
    }
}
