package com.chefpay.server.pricing;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.domain.PriceChangeSuggestion;
import com.chefpay.core.domain.PriceChangeSuggestionStatus;
import com.chefpay.core.domain.Recipe;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.MenuItemRepository;
import com.chefpay.core.repository.PriceChangeSuggestionRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.notifications.NotificationService;
import com.chefpay.server.recipe.RecipeService;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "Advisory Dynamic Pricing Suggestions" (F3.2). {@link #detectAndRaiseAll} is the whole detection
 * algorithm: for every active {@link MenuItem} with a costed {@link Recipe}, if its current margin
 * (price vs. recipe cost) has fallen to or below {@code Restaurant#getMarginErosionThresholdPercent()},
 * raise a {@link PriceChangeSuggestion} proposing a new price that would exactly restore the margin
 * to that same threshold - never higher, so the suggestion is always the smallest change that fixes
 * the erosion, not an aggressive markup.
 *
 * <p>The system itself never changes a live price on its own initiative - raising a suggestion is
 * the only thing {@link #detectAndRaiseAll} does. But per the SRS's own wording ("require explicit
 * Level-2/3 approval to <i>apply</i>... every <i>applied</i> change is logged"), an explicit human
 * {@link #apply} call - gated on {@code MENU_MANAGE} at the controller, ChefPay's Level-2+ ("Shift
 * Supervisor" and above) equivalent - DOES write {@link MenuItem#getPrice()} in the same
 * transaction, audit-logged with the suggestion's reason. This mirrors {@code ReplenishmentService}
 * ("suggests, never auto-creates a PO") for the detection half, while still honoring F3.2's "apply"
 * semantics for the human-approval half - an earlier version of this service left {@code MenuItem
 * .price} untouched even after "approval," which under-delivered the requirement; fixed here.
 */
@Service
@RequiredArgsConstructor
public class PriceSuggestionService {

    private final MenuItemRepository menuItemRepository;
    private final RecipeService recipeService;
    private final RestaurantRepository restaurantRepository;
    private final PriceChangeSuggestionRepository suggestionRepository;
    private final AppUserRepository appUserRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final WebSocketEventPublisher eventPublisher;

    @Transactional
    public List<PriceChangeSuggestion> detectAndRaiseAll() {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
        BigDecimal thresholdPercent = restaurant.getMarginErosionThresholdPercent();
        List<PriceChangeSuggestion> raised = new ArrayList<>();

        for (MenuItem item : menuItemRepository.findByActiveTrueOrderByNameAsc()) {
            Optional<Recipe> recipeOpt = recipeService.findByMenuItem(item.getId());
            if (recipeOpt.isEmpty() || !recipeOpt.get().isActive()) {
                continue;
            }
            BigDecimal cost = recipeService.costPerServing(recipeOpt.get());
            if (cost == null || item.getPrice() == null || item.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal marginPercent = item.getPrice().subtract(cost)
                    .divide(item.getPrice(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            if (marginPercent.compareTo(thresholdPercent) > 0) {
                continue; // margin is healthy - nothing to suggest
            }
            if (suggestionRepository.findFirstByMenuItemIdAndStatusOrderByDetectedAtDesc(item.getId(), PriceChangeSuggestionStatus.PENDING).isPresent()) {
                continue; // an earlier suggestion for this item is still awaiting a decision
            }

            BigDecimal targetMarginFraction = thresholdPercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            BigDecimal denominator = BigDecimal.ONE.subtract(targetMarginFraction);
            if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // a >=100% threshold is not a sane configuration - nothing sensible to suggest
            }
            BigDecimal suggestedPrice = cost.divide(denominator, 2, RoundingMode.HALF_UP);
            if (suggestedPrice.compareTo(item.getPrice()) <= 0) {
                continue; // guard against a rounding edge case producing a non-increase
            }

            String reason = "Recipe cost is now " + cost.setScale(2, RoundingMode.HALF_UP) + " against a price of "
                    + item.getPrice().setScale(2, RoundingMode.HALF_UP) + " (" + marginPercent.setScale(2, RoundingMode.HALF_UP)
                    + "% margin) - at or below the configured " + thresholdPercent + "% threshold.";
            PriceChangeSuggestion suggestion = PriceChangeSuggestion.builder()
                    .menuItem(item)
                    .currentPrice(item.getPrice())
                    .currentRecipeCost(cost)
                    .currentMarginPercent(marginPercent.setScale(2, RoundingMode.HALF_UP))
                    .suggestedPrice(suggestedPrice)
                    .projectedMarginPercent(thresholdPercent)
                    .reason(reason)
                    .detectedAt(LocalDateTime.now())
                    .build();
            PriceChangeSuggestion saved = suggestionRepository.save(suggestion);
            raised.add(saved);
            notificationService.create("PRICE_SUGGESTION",
                    "Margin on " + item.getName() + " has eroded to " + marginPercent.setScale(1, RoundingMode.HALF_UP)
                            + "% - a price change is suggested.", saved.getId());
        }
        return raised;
    }

    @Transactional(readOnly = true)
    public List<PriceChangeSuggestion> listPending() {
        return suggestionRepository.findByStatusOrderByDetectedAtDesc(PriceChangeSuggestionStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<PriceChangeSuggestion> listAll() {
        return suggestionRepository.findByOrderByDetectedAtDesc();
    }

    /**
     * F3.2's "apply" action - Level-2/3 gated at the controller ({@code MENU_MANAGE}). Writes the
     * suggested price onto the live {@link MenuItem} in the same transaction and logs the change
     * with the suggestion's reason, matching the SRS's exact wording ("require explicit Level-2/3
     * approval to apply... every applied change is logged"). Refuses if the item's price has moved
     * since the suggestion was raised (someone already edited it manually in the meantime) rather
     * than silently overwriting that edit - the manager should re-scan and review the fresh numbers.
     */
    @Transactional
    public PriceChangeSuggestion apply(UUID id, String note, UUID actorUserId) {
        PriceChangeSuggestion suggestion = suggestionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Price change suggestion not found"));
        if (suggestion.getStatus() != PriceChangeSuggestionStatus.PENDING) {
            throw ApiException.conflict("SUGGESTION_ALREADY_DECIDED", "This suggestion has already been " + suggestion.getStatus() + ".");
        }
        MenuItem item = menuItemRepository.findById(suggestion.getMenuItem().getId())
                .orElseThrow(() -> ApiException.notFound("Menu item not found"));
        if (item.getPrice() == null || item.getPrice().compareTo(suggestion.getCurrentPrice()) != 0) {
            throw ApiException.conflict("PRICE_CHANGED_SINCE_SUGGESTION",
                    "This item's price has changed since the suggestion was raised (now " + item.getPrice()
                            + ", suggestion assumed " + suggestion.getCurrentPrice() + ") - re-scan for a fresh suggestion.");
        }

        BigDecimal oldPrice = item.getPrice();
        item.setPrice(suggestion.getSuggestedPrice());
        MenuItem savedItem = menuItemRepository.save(item);

        AppUser user = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        suggestion.setStatus(PriceChangeSuggestionStatus.APPLIED);
        suggestion.setDecidedBy(user);
        suggestion.setDecidedAt(LocalDateTime.now());
        suggestion.setDecisionNote(note);
        PriceChangeSuggestion saved = suggestionRepository.save(suggestion);

        auditService.record(actorUserId, null, "MenuItem", savedItem.getId(), "PRICE_APPLIED_FROM_SUGGESTION",
                oldPrice.toPlainString(), savedItem.getPrice().toPlainString(), suggestion.getReason() + (note == null || note.isBlank() ? "" : " | " + note),
                CorrelationIdHolder.get());

        eventPublisher.publish("/topic/menu", "MENU_PRICE_CHANGED", savedItem.getId(), savedItem.getVersion(),
                Map.of("itemName", savedItem.getName(), "oldPrice", oldPrice, "newPrice", savedItem.getPrice()));

        return saved;
    }

    /** F3.2's "dismiss" action - a manager reviewed the suggestion and chose not to change the
     * price at all; the live {@link MenuItem} is untouched. */
    @Transactional
    public PriceChangeSuggestion dismiss(UUID id, String note, UUID actorUserId) {
        PriceChangeSuggestion suggestion = suggestionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Price change suggestion not found"));
        if (suggestion.getStatus() != PriceChangeSuggestionStatus.PENDING) {
            throw ApiException.conflict("SUGGESTION_ALREADY_DECIDED", "This suggestion has already been " + suggestion.getStatus() + ".");
        }
        AppUser user = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        suggestion.setStatus(PriceChangeSuggestionStatus.DISMISSED);
        suggestion.setDecidedBy(user);
        suggestion.setDecidedAt(LocalDateTime.now());
        suggestion.setDecisionNote(note);
        PriceChangeSuggestion saved = suggestionRepository.save(suggestion);

        auditService.record(actorUserId, null, "PriceChangeSuggestion", saved.getId(), "PRICE_SUGGESTION_DISMISSED",
                null, saved.getMenuItem().getName(), note, CorrelationIdHolder.get());
        return saved;
    }
}
