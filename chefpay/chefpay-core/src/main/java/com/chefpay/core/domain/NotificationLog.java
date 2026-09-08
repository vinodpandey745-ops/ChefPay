package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Round 14 (F4.2) - one row per dispatch ATTEMPT of a critical alert on one {@link
 * #channel}, whether it succeeded, was skipped (channel not configured), or failed. Deliberately
 * one row per channel per alert rather than one row per alert (an anomaly alerted over Email +
 * WhatsApp + in-app produces three rows) - see {@code AlertDispatchService}'s javadoc for why:
 * each channel has its own independent success/failure outcome and this is the audit trail
 * {@code AlertsView}'s "Delivery Log" reads to answer "did the manager's phone actually get this."
 */
@Entity
@Table(name = "notification_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class NotificationLog extends BaseEntity {

    @ManyToOne
    @JoinColumn(name = "anomaly_id")
    private Anomaly anomaly;

    @Column(nullable = false, length = 40)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private NotificationChannel channel;

    private String recipient;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private NotificationLogStatus status;

    @Column(length = 1000)
    private String errorMessage;

    /** True when this row was raised by the escalation job re-notifying on an anomaly still
     * unreviewed after {@code Restaurant#getCriticalAlertEscalationMinutes()} - distinguishes a
     * routine first alert from a "nobody's looked at this yet" escalation in the delivery log. */
    @Builder.Default
    @Column(nullable = false)
    private boolean escalation = false;

    @Column(nullable = false)
    private LocalDateTime attemptedAt;
}
