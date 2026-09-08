package com.chefpay.server.pricing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Round 14 (F3.2) - runs the margin-erosion scan once a day; a manager can also trigger it
 * on-demand via {@code POST /api/pricing/suggestions/scan} (the "Rescan" button on the Dynamic
 * Pricing screen) without waiting for this schedule. */
@Component
@RequiredArgsConstructor
@Slf4j
public class PriceSuggestionScheduler {

    private final PriceSuggestionService priceSuggestionService;

    @Scheduled(cron = "0 0 5 * * *")
    public void runDailyScan() {
        try {
            priceSuggestionService.detectAndRaiseAll();
        } catch (Exception ex) {
            log.error("Daily price-suggestion scan failed: {}", ex.getMessage(), ex);
        }
    }
}
