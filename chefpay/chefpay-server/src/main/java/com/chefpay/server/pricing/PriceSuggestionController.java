package com.chefpay.server.pricing;

import com.chefpay.core.domain.PriceChangeSuggestion;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** F3.2 - dynamic pricing suggestion review. {@code MENU_MANAGE} gates both viewing and
 * deciding: this is a menu-pricing action, same audience as editing {@code MenuItem.price} itself,
 * and matches ChefPay's Level-2+ ("Shift Supervisor" and above) equivalent the SRS calls for.
 * {@code /apply} names the endpoint exactly as the SRS's Section 9.2 API sketch does (POST
 * /api/v1/pricing/suggestions/{id}/apply) and actually writes the new price - see {@code
 * PriceSuggestionService#apply}'s javadoc. */
@RestController
@RequestMapping("/api/pricing/suggestions")
@RequiredArgsConstructor
public class PriceSuggestionController {

    private final PriceSuggestionService priceSuggestionService;

    @GetMapping
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<List<PriceSuggestionDtos.SuggestionDto>> list(@RequestParam(required = false, defaultValue = "false") boolean pendingOnly) {
        List<PriceChangeSuggestion> suggestions = pendingOnly ? priceSuggestionService.listPending() : priceSuggestionService.listAll();
        return ApiResponse.ok(suggestions.stream().map(this::toDto).toList());
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<List<PriceSuggestionDtos.SuggestionDto>> scanNow() {
        return ApiResponse.ok(priceSuggestionService.detectAndRaiseAll().stream().map(this::toDto).toList());
    }

    @PostMapping("/{id}/apply")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<PriceSuggestionDtos.SuggestionDto> apply(@PathVariable UUID id, @RequestBody(required = false) PriceSuggestionDtos.DecideRequest request,
                                                                 @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(toDto(priceSuggestionService.apply(id, request == null ? null : request.note(), userId(principal))));
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<PriceSuggestionDtos.SuggestionDto> dismiss(@PathVariable UUID id, @RequestBody(required = false) PriceSuggestionDtos.DecideRequest request,
                                                                   @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(toDto(priceSuggestionService.dismiss(id, request == null ? null : request.note(), userId(principal))));
    }

    private PriceSuggestionDtos.SuggestionDto toDto(PriceChangeSuggestion s) {
        return new PriceSuggestionDtos.SuggestionDto(s.getId(), s.getMenuItem().getId(), s.getMenuItem().getName(),
                s.getCurrentPrice(), s.getCurrentRecipeCost(), s.getCurrentMarginPercent(), s.getSuggestedPrice(),
                s.getProjectedMarginPercent(), s.getReason(), s.getStatus().name(), s.getDetectedAt(),
                s.getDecidedBy() == null ? null : s.getDecidedBy().getDisplayName(), s.getDecidedAt(), s.getDecisionNote(), s.getVersion());
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
