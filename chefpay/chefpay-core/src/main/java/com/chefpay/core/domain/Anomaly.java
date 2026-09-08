package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One flagged event from the Tier-1 fraud/loss-prevention rule engine (AI Backbone Addendum F1.5)
 * or the F1.1 aggregator-mismatch check. Deliberately folds what the requirements doc calls
 * "AnomalyResolution" directly onto this entity ({@link #resolutionType}/{@link #resolutionNote}/
 * {@link #resolvedBy}/{@link #resolvedAt}) rather than a separate child table - same "soft
 * resolution fields on the row itself" convention {@link Payment#isVoided()}/{@code voidReason}
 * already use in this codebase, and an anomaly has exactly one resolution, never a history of them.
 *
 * <p>{@link #eodSession} is nullable: event-triggered rules (see {@code FraudRuleEngineService})
 * create an Anomaly the moment the underlying POS action happens, which may be well before that
 * business date's {@link EodSession} even exists - it gets linked once the EOD Review step queries
 * "all anomalies for this business date", same lookup either way ({@link #businessDate} is always
 * set independently of whether a session row exists yet).
 */
@Entity
@Table(name = "anomaly")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Anomaly extends BaseEntity {

    @Column(nullable = false)
    private LocalDate businessDate;

    /** Round 27 (pre-deployment audit) fix: {@code @ManyToOne} defaults to EAGER when no fetch is
     * given, and {@link EodSession} carries two large text columns ({@code zReportText} and the
     * base64 {@code zReportPdfBase64}) - eagerly pulling those in on every anomaly is the same
     * shape as the {@code Restaurant.logoImageBase64} OOM bug already fixed elsewhere, and this one
     * is worse: {@code AlertEscalationScheduler} runs every 5 minutes and loads every unreviewed
     * anomaly. LAZY is safe: every real caller runs inside an HTTP request thread or a
     * {@code @Transactional} scheduled method, both covered by {@code open-in-view=true}/an open
     * Hibernate session for the method's duration. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "eod_session_id")
    private EodSession eodSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private FraudRuleCode ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnomalySeverity severity;

    /** Short human grouping shown on the review card, e.g. "Sweethearting", "Cash Handling",
     * "Aggregator Reconciliation" - distinct from {@link #ruleCode} (the stable machine key). */
    @Column(nullable = false)
    private String category;

    @Column(nullable = false, length = 2000)
    private String description;

    /** What kind of thing {@link #referenceEntityId} points at, e.g. "Order", "Payment", "AppUser". */
    private String referenceEntityType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID referenceEntityId;

    /** Convenience direct link to the underlying order, when there is one - the review screen's
     * event timeline (F1.6) reads this order's audit trail without having to first resolve
     * {@link #referenceEntityType}/{@link #referenceEntityId}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID referenceOrderId;

    @Column(precision = 12, scale = 2)
    private BigDecimal amountImpact;

    @ManyToOne
    @JoinColumn(name = "involved_user_id")
    private AppUser involvedUser;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID involvedDeviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private AnomalyStatus status = AnomalyStatus.UNREVIEWED;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    // ---- Resolution (F1.6) ----

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AnomalyResolutionType resolutionType;

    /** Fixed dropdown reason (F1.6 Section 11.2), required only when {@link #resolutionType} is
     * {@code CONFIRMED_THEFT}: "EMPLOYEE_THEFT" / "UNVERIFIED_VOID" / "POLICY_ABUSE" / "OTHER". */
    @Column(length = 50)
    private String escalationReasonCode;

    @Column(length = 2000)
    private String resolutionNote;

    @ManyToOne
    @JoinColumn(name = "resolved_by")
    private AppUser resolvedBy;

    private LocalDateTime resolvedAt;

    // ---- Round 14, F4.4: CCTV timestamp linking ----

    /** A direct link into the restaurant's own CCTV/NVR system, pre-seeked to this anomaly's
     * {@link #detectedAt} moment - null until a {@code com.chefpay.plugin.api.CctvProvider} plugin
     * is installed and its {@code buildTimestampLinkedUrl} call succeeds (see that interface's
     * javadoc: ships with zero built-in implementations, same as every other "no real vendor to
     * integrate against yet" extension point in this codebase). Populated by {@code
     * CctvLinkService#tryLinkFootage}, called once when an anomaly is first created - never
     * retried automatically, since a plugin installed later has no way to backfill footage for an
     * anomaly whose retention window has since expired anyway. */
    @Column(length = 500)
    private String cctvFootageUrl;
}
