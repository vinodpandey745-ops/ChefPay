package com.chefpay.server.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Final round - runs {@link DataRetentionService#runPurge()} once a day at a quiet overnight hour
 * (well before {@code AutoReplenishmentScheduler}'s 6 AM run and long after {@code
 * AiNightlySummaryScheduler}'s 11:30 PM run, so it never contends with either for the same
 * SQLite/Postgres connection window). {@link DataRetentionService#runPurge()} itself no-ops
 * instantly unless a restaurant has explicitly opted in via {@code Restaurant#isAutoPurgeEnabled()}
 * - most deployments pay nothing for this job simply existing.
 *
 * <p>Matches {@code AutoReplenishmentScheduler}'s exact defensive pattern: the single service call
 * is wrapped in try/catch and any failure is only logged, never rethrown - per the user's explicit
 * instruction that "any DB issue should not hamper or crash the application," a failed purge run
 * must never take down the app, it should simply try again on the next scheduled run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataRetentionScheduler {

    private final DataRetentionService dataRetentionService;

    @Scheduled(cron = "0 0 3 * * *")
    public void runDailyPurge() {
        try {
            dataRetentionService.runPurge();
        } catch (Exception ex) {
            log.error("Data-retention auto-purge run failed: {}", ex.getMessage(), ex);
        }
    }
}
