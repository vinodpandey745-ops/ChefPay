package com.chefpay.server.ai;

import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Round 14 (F4.1). {@code interpret} only requires {@code AI_USE} (read-only, proposes nothing
 * executed yet); {@code execute} additionally requires {@code MENU_MANAGE} since it's the same
 * mutation {@code MenuController#updateItem}'s availability toggle already gates - the NL
 * assistant is a new way to REACH that action, not a way around its permission.
 *
 * <p>Bistrodesk Phase 4 (requirement #24): requires the {@code AI_FEATURES} plan feature - see
 * {@link RequiresFeature}'s javadoc.
 */
@RestController
@RequestMapping("/api/ai/ops")
@RequiredArgsConstructor
@RequiresFeature("AI_FEATURES")
public class AiOpsAssistantController {

    private final AiOpsAssistantService aiOpsAssistantService;

    @PostMapping("/command/interpret")
    @PreAuthorize("hasAuthority('AI_USE')")
    public ApiResponse<AiOpsDtos.InterpretedCommandDto> interpret(@Valid @RequestBody AiOpsDtos.InterpretCommandRequest request) {
        return ApiResponse.ok(aiOpsAssistantService.interpret(request.instruction()));
    }

    @PostMapping("/command/execute-toggle-availability")
    @PreAuthorize("hasAuthority('AI_USE') and hasAuthority('MENU_MANAGE')")
    public ApiResponse<AiOpsDtos.ExecutedCommandDto> executeToggle(@Valid @RequestBody AiOpsDtos.ExecuteToggleAvailabilityRequest request,
                                                                    @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(aiOpsAssistantService.executeToggleAvailability(request.menuItemId(), request.available(),
                request.expectedVersion(), userId(principal)));
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
