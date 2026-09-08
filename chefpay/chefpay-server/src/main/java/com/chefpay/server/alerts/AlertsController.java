package com.chefpay.server.alerts;

import com.chefpay.core.domain.NotificationLog;
import com.chefpay.core.repository.NotificationLogRepository;
import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Round 14 (F4.2) - read-only delivery log for critical anomaly alerts, gated behind the same
 * {@code EOD_MANAGE}/{@code AUDIT_VIEW} audience that already reviews anomalies (Round 13's EOD
 * Manager Audit Review screen) - this is purely "did the alert actually go out," not a new
 * permission surface of its own.
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertsController {

    private final NotificationLogRepository notificationLogRepository;

    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('EOD_MANAGE') or hasAuthority('AUDIT_VIEW')")
    public ApiResponse<List<AlertDtos.NotificationLogDto>> listLogs() {
        return ApiResponse.ok(notificationLogRepository.findByOrderByAttemptedAtDesc().stream().map(this::toDto).toList());
    }

    @GetMapping("/logs/anomaly/{anomalyId}")
    @PreAuthorize("hasAuthority('EOD_MANAGE') or hasAuthority('AUDIT_VIEW')")
    public ApiResponse<List<AlertDtos.NotificationLogDto>> listForAnomaly(@PathVariable UUID anomalyId) {
        return ApiResponse.ok(notificationLogRepository.findByAnomalyIdOrderByAttemptedAtDesc(anomalyId).stream().map(this::toDto).toList());
    }

    private AlertDtos.NotificationLogDto toDto(NotificationLog log) {
        return new AlertDtos.NotificationLogDto(log.getId(), log.getAnomaly() == null ? null : log.getAnomaly().getId(),
                log.getCategory(), log.getChannel().name(), log.getRecipient(), log.getSubject(), log.getMessage(),
                log.getStatus().name(), log.getErrorMessage(), log.isEscalation(), log.getAttemptedAt());
    }
}
