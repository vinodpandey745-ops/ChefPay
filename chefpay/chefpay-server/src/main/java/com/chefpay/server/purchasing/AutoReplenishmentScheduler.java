package com.chefpay.server.purchasing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Round 14 (F3.1) - runs {@link ReplenishmentService#runAutoPoGeneration()} once a day, deliberately
 * scheduled well before {@code AiNightlySummaryScheduler}'s 11:30 PM run so a fresh day's draft POs
 * (if any) are already sitting in the Purchase Orders screen well before a manager's morning check,
 * not generated overnight while nobody's looking at the dashboard anyway.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoReplenishmentScheduler {

    private final ReplenishmentService replenishmentService;

    @Scheduled(cron = "0 0 6 * * *")
    public void runDailyAutoPoGeneration() {
        try {
            replenishmentService.runAutoPoGeneration();
        } catch (Exception ex) {
            log.error("Auto-PO generation run failed: {}", ex.getMessage(), ex);
        }
    }
}
