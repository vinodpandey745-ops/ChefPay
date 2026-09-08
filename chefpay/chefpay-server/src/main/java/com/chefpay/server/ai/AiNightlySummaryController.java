package com.chefpay.server.ai;

import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manual "Generate Now" trigger for Feature F, so turning the feature on doesn't mean waiting
 * until 11:30 PM to see what it does - see {@link AiNightlySummaryService} for the shared logic
 * also used by the nightly {@link AiNightlySummaryScheduler}.
 *
 * <p>Bistrodesk Phase 4 (requirement #24): requires the {@code AI_FEATURES} plan feature - see
 * {@link RequiresFeature}'s javadoc. The nightly scheduler itself (not a controller, so outside
 * this AOP gate's reach) is left to {@code AiNightlySummaryScheduler}'s own judgment - a
 * genuinely separate, smaller fix tracked there rather than folded into this one. */
@RestController
@RequestMapping("/api/ai/nightly-summary")
@RequiredArgsConstructor
@RequiresFeature("AI_FEATURES")
public class AiNightlySummaryController {

    private final AiNightlySummaryService nightlySummaryService;

    @PostMapping("/generate-now")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW') and hasAuthority('AI_USE')")
    public ApiResponse<AiNightlySummaryDtos.GenerateNowResponse> generateNow() {
        String summary = nightlySummaryService.generateAndNotify();
        return ApiResponse.ok(new AiNightlySummaryDtos.GenerateNowResponse(summary));
    }
}
