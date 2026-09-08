package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row per business date (AI Backbone Addendum F1.1) - the guided End-of-Day wizard:
 * Ingest -&gt; Blind Cash Count -&gt; Fraud/Audit Review -&gt; Finalize &amp; GL Sync. {@link #status}
 * doubles as "which wizard step are we on", so a crashed/network-interrupted EOD simply resumes
 * by re-fetching this row and rendering whatever step its status says (F1.1: "must restart from
 * the last completed step, not from zero").
 *
 * <p><b>Scope note (Round 13):</b> kept restaurant-wide (one active session per business date)
 * rather than per-branch. {@code CashMovement}/{@code Payment} carry no branch reference anywhere
 * in this codebase today, so a per-branch cash reconciliation has nothing to scope against yet -
 * see the Round 13 report's "deferred to Round 14" section. Everything else here (channel
 * ingestion, fraud rules, anomaly review) works the same restaurant-wide today and can be split
 * per-branch later without changing this entity's shape once that dependency exists.
 */
@Entity
@Table(name = "eod_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class EodSession extends BaseEntity {

    @Column(nullable = false, unique = true)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EodSessionStatus status = EodSessionStatus.INGESTING;

    /** Snapshot of {@code Restaurant#defaultOpeningFloat} at the moment this session started -
     * captured rather than read live from Restaurant at cash-count time, so changing the default
     * later never silently rewrites the expected-cash math for an already-open or historical
     * session. */
    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal openingFloat = BigDecimal.ZERO;

    @ManyToOne
    @JoinColumn(name = "started_by")
    private AppUser startedBy;

    @ManyToOne
    @JoinColumn(name = "finalized_by")
    private AppUser finalizedBy;

    private LocalDateTime finalizedAt;

    /** Free-text reason, required only when {@link #finalizedWithOverride} is true (F1.1: "EOD
     * cannot be finalized while any channel ingestion is FAILED... unless a Level-3 user explicitly
     * overrides with a logged reason", and F1.6: "Finalize disabled while any High/Critical anomaly
     * remains Unreviewed" unless the same Level-3 override path is used). */
    @Column(length = 1000)
    private String finalizeOverrideReason;

    @Builder.Default
    private boolean finalizedWithOverride = false;

    /** Always populated on finalize - the synchronous, always-available summary (mirrors
     * {@code BillingService#generateReceiptText}'s plain-text convention). See {@link #zReportPdfBase64}
     * for the PDF rendering of the same numbers. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String zReportText;

    /** Base64-encoded PDF (OpenPDF), same storage convention as {@code Restaurant#logoImageBase64}.
     * Generated synchronously within {@link #finalizedAt}'s transaction for Round 13 - see the
     * Round 13 report for why this deliberately doesn't follow F1.1's technical note about async
     * generation (small, already-aggregated data set; avoids introducing this codebase's first
     * background-job/polling pattern for one report). */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String zReportPdfBase64;

    /** GL sync outcome, if a {@code GlSyncAdapter} was configured - "NOT_CONFIGURED" (default, no
     * adapter wired - F1.7: "where an integration is configured", implying skip is normal, not an
     * error) / "SUCCESS" / "FAILED". */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String glSyncStatus = "NOT_CONFIGURED";

    @Column(length = 1000)
    private String glSyncMessage;
}
