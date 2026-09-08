package com.chefpay.server.alerts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Round 14 (F4.2) - runs {@link AlertDispatchService#runEscalationSweep()} every 5 minutes.
 * Requires {@code @EnableScheduling} on {@code ChefPayServerApplication}, already turned on for
 * {@code AiNightlySummaryScheduler} - this piggybacks on the same Spring scheduling
 * infrastructure rather than needing anything new enabled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlertEscalationScheduler {

    private final AlertDispatchService alertDispatchService;

    @Scheduled(cron = "0 */5 * * * *")
    public void runSweep() {
        try {
            alertDispatchService.runEscalationSweep();
        } catch (Exception ex) {
            log.error("Alert escalation sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
