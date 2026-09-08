package com.chefpay.server.ai;

import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires {@link AiNightlySummaryService#generateAndNotify()} once a day at 11:30 PM server time.
 * Silently skips (debug log only) when AI Features/the nightly-summary switch/API key aren't
 * configured - most restaurants that never turn this on should never see a log line about it, same
 * as {@code NotificationService#create}'s "never break the caller" posture elsewhere in this app.
 * Requires {@code @EnableScheduling} on {@code ChefPayServerApplication} to actually run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiNightlySummaryScheduler {

    private final AiNightlySummaryService nightlySummaryService;

    @Scheduled(cron = "0 30 23 * * *")
    public void runNightlySummary() {
        try {
            nightlySummaryService.generateAndNotify();
        } catch (ApiException ex) {
            log.debug("Nightly AI summary skipped: {}", ex.getMessage());
        } catch (Exception ex) {
            log.error("Nightly AI summary failed: {}", ex.getMessage(), ex);
        }
    }
}
