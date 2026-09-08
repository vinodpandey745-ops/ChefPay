package com.chefpay.server.restaurant;

import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2's {@code /api/organization} name from PHASE2_ORG_SUBSCRIPTION_DESIGN.md's API table -
 * a thin alias over {@link RestaurantController}, not a second copy of its logic: "Organization"
 * IS the existing (enriched) {@code Restaurant} row, not a separate entity - see {@code
 * Restaurant}'s Round 17/Phase 2 javadoc. Kept as its own controller/path (rather than adding a
 * second {@code @RequestMapping} to {@code RestaurantController}, which Spring MVC doesn't support
 * on one class) purely so the Manager/Admin "Organization" screen can call a name that matches what
 * it manages, while every actual field/permission/audit rule lives in exactly one place.
 */
@RestController
@RequestMapping("/api/organization")
@RequiredArgsConstructor
public class OrganizationController {

    private final RestaurantController restaurantController;

    @GetMapping
    public ApiResponse<RestaurantDto> get(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return restaurantController.get(principal);
    }

    @PatchMapping
    public ApiResponse<RestaurantDto> update(@Valid @RequestBody UpdateRestaurantRequest request,
                                              @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return restaurantController.update(request, principal);
    }
}
