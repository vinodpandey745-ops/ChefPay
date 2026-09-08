package com.chefpay.server.alerts;

import com.chefpay.core.domain.Anomaly;
import com.chefpay.core.domain.AnomalySeverity;
import com.chefpay.core.domain.NotificationChannel;
import com.chefpay.core.domain.NotificationLog;
import com.chefpay.core.domain.NotificationLogStatus;
import com.chefpay.core.domain.AnomalyStatus;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.AnomalyRepository;
import com.chefpay.core.repository.NotificationLogRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.plugin.api.CriticalAlertChannel;
import com.chefpay.plugin.api.dto.NotificationResult;
import com.chefpay.server.billing.EmailReceiptService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.notifications.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Round 14 (F4.2) - "Push/WhatsApp/Email Alerting for Critical Anomalies." Fires once, immediately,
 * whenever {@code FraudRuleEngineService} persists a new {@link Anomaly} ({@code escalation=false}),
 * and again later by {@link AlertEscalationScheduler} if it's still unreviewed past {@code
 * Restaurant#getCriticalAlertEscalationMinutes()} ({@code escalation=true}). Every dispatch attempt
 * on every channel - including a channel that isn't configured - is recorded as one {@link
 * NotificationLog} row, so "did anyone actually get told about this" is always answerable from the
 * delivery log rather than inferred from silence.
 *
 * <p>Deliberately restricted to {@code HIGH}/{@code CRITICAL} severity for every channel beyond
 * the always-on in-app inbox: paging a manager's phone for a {@code LOW}/{@code MEDIUM} anomaly
 * would train them to ignore the channel entirely (alert fatigue) - the in-app Alerts inbox
 * already surfaces every severity for anyone who wants to review the full list.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertDispatchService {

    private final NotificationLogRepository notificationLogRepository;
    private final RestaurantRepository restaurantRepository;
    private final AnomalyRepository anomalyRepository;
    private final EmailReceiptService emailReceiptService;
    private final NotificationService notificationService;
    private final List<CriticalAlertChannel> alertChannels;

    @Transactional
    public void dispatchForAnomaly(Anomaly anomaly, boolean escalation) {
        String title = "[" + anomaly.getSeverity() + "] " + anomaly.getCategory() + (escalation ? " (ESCALATION)" : "");
        String message = anomaly.getDescription();

        notificationService.create(escalation ? "FRAUD_ANOMALY_ESCALATION" : "FRAUD_ANOMALY", title + " - " + message, anomaly.getId());
        log(anomaly, NotificationChannel.IN_APP, null, title, message, NotificationLogStatus.SENT, null, escalation);

        if (anomaly.getSeverity() != AnomalySeverity.HIGH && anomaly.getSeverity() != AnomalySeverity.CRITICAL) {
            return;
        }

        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        if (restaurant == null) {
            return;
        }
        dispatchEmail(restaurant, anomaly, title, message, escalation);
        dispatchPluginChannel(anomaly, NotificationChannel.WHATSAPP, restaurant.getSupportPhone(), title, message, escalation);
        dispatchPluginChannel(anomaly, NotificationChannel.SMS, restaurant.getSupportPhone(), title, message, escalation);
        dispatchPluginChannel(anomaly, NotificationChannel.PUSH, restaurant.getSupportPhone(), title, message, escalation);
    }

    private void dispatchEmail(Restaurant restaurant, Anomaly anomaly, String title, String message, boolean escalation) {
        String recipients = restaurant.getCriticalAlertRecipientEmails();
        if (recipients == null || recipients.isBlank()) {
            log(anomaly, NotificationChannel.EMAIL, null, title, message, NotificationLogStatus.SKIPPED_NOT_CONFIGURED, null, escalation);
            return;
        }
        for (String address : recipients.split(",")) {
            String trimmed = address.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                emailReceiptService.sendDocument(trimmed, "Bistrodesk Alert - " + title, message);
                log(anomaly, NotificationChannel.EMAIL, trimmed, title, message, NotificationLogStatus.SENT, null, escalation);
            } catch (ApiException ex) {
                log(anomaly, NotificationChannel.EMAIL, trimmed, title, message, NotificationLogStatus.FAILED, ex.getMessage(), escalation);
            } catch (Exception ex) {
                log.error("Unexpected failure emailing critical alert to {} for anomaly {}.", trimmed, anomaly.getId(), ex);
                log(anomaly, NotificationChannel.EMAIL, trimmed, title, message, NotificationLogStatus.FAILED, ex.getMessage(), escalation);
            }
        }
    }

    private void dispatchPluginChannel(Anomaly anomaly, NotificationChannel channel, String recipient,
                                        String title, String message, boolean escalation) {
        CriticalAlertChannel provider = alertChannels.stream()
                .filter(p -> channel.name().equalsIgnoreCase(p.getSupportedChannel()))
                .findFirst().orElse(null);
        if (provider == null) {
            log(anomaly, channel, recipient, title, message, NotificationLogStatus.SKIPPED_NOT_CONFIGURED, null, escalation);
            return;
        }
        try {
            NotificationResult result = provider.sendAlert(recipient, title, message);
            log(anomaly, channel, recipient, title, message,
                    result.success() ? NotificationLogStatus.SENT : NotificationLogStatus.FAILED,
                    result.success() ? null : result.message(), escalation);
        } catch (Exception ex) {
            log.error("Critical alert channel {} failed for anomaly {}.", channel, anomaly.getId(), ex);
            log(anomaly, channel, recipient, title, message, NotificationLogStatus.FAILED, ex.getMessage(), escalation);
        }
    }

    /** Called on a schedule by {@link AlertEscalationScheduler}. Re-alerts, exactly once per
     * anomaly, any still-{@code UNREVIEWED} High/Critical anomaly older than {@code
     * Restaurant#getCriticalAlertEscalationMinutes()} - "exactly once" is enforced by checking
     * whether a prior {@code escalation=true} log row already exists for it, not by any separate
     * flag on {@code Anomaly} itself, so this stays idempotent across however often the scheduler
     * actually runs. */
    @Transactional
    public void runEscalationSweep() {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        if (restaurant == null) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(restaurant.getCriticalAlertEscalationMinutes());
        List<Anomaly> unreviewed = anomalyRepository.findByStatus(AnomalyStatus.UNREVIEWED);
        for (Anomaly anomaly : unreviewed) {
            boolean severeEnough = anomaly.getSeverity() == AnomalySeverity.HIGH || anomaly.getSeverity() == AnomalySeverity.CRITICAL;
            if (!severeEnough || anomaly.getDetectedAt() == null || anomaly.getDetectedAt().isAfter(cutoff)) {
                continue;
            }
            boolean alreadyEscalated = notificationLogRepository.findByAnomalyIdOrderByAttemptedAtDesc(anomaly.getId())
                    .stream().anyMatch(NotificationLog::isEscalation);
            if (alreadyEscalated) {
                continue;
            }
            dispatchForAnomaly(anomaly, true);
        }
    }

    private void log(Anomaly anomaly, NotificationChannel channel, String recipient, String subject, String message,
                      NotificationLogStatus status, String errorMessage, boolean escalation) {
        try {
            notificationLogRepository.save(NotificationLog.builder()
                    .anomaly(anomaly)
                    .category("FRAUD_ANOMALY")
                    .channel(channel)
                    .recipient(recipient)
                    .subject(subject)
                    .message(message)
                    .status(status)
                    .errorMessage(errorMessage)
                    .escalation(escalation)
                    .attemptedAt(LocalDateTime.now())
                    .build());
        } catch (Exception ex) {
            // Same "a notification/log write must never break the caller" discipline as
            // NotificationService#create - losing one delivery-log row is far preferable to
            // rolling back the anomaly this alert is about.
            log.error("Failed to write NotificationLog for anomaly {} channel {}: {}", anomaly.getId(), channel, ex.getMessage(), ex);
        }
    }
}
