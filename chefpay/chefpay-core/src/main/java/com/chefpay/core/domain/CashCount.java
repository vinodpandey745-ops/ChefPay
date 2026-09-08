package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
import java.time.LocalDateTime;

/**
 * Blind physical cash-drawer count for one {@link EodSession} (AI Backbone Addendum F1.2 - "manager
 * enters physical cash counted by denomination without seeing the system-expected total first").
 * {@code expectedTotal} is deliberately only computed and stored by the server AFTER
 * {@code physicalTotal} is submitted (see {@code EodService#submitCashCount}) - it is never sent to
 * the client beforehand, which is what actually makes the count "blind" rather than the UI simply
 * choosing not to display a number it already has.
 *
 * <p>{@code denominationBreakdownJson} is a JSON array of {@code {"label":"...","value":N,"count":N}}
 * entries - kept as free-form denomination/value pairs (not a fixed enum of INR notes) so this
 * works for any {@code Restaurant#currencySymbol}, matching NFR-7's "denomination sets... must be
 * store-configurable, not hard-coded" - the JavaFX client is what actually offers a sensible
 * default set per currency; the server only needs to sum {@code value * count} across whatever list
 * it's given.
 */
@Entity
@Table(name = "cash_count")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class CashCount extends BaseEntity {

    /** Round 27 (pre-deployment audit) fix: same EAGER-by-default trap as {@code Anomaly#eodSession}
     * - see that field's javadoc for the full reasoning ({@link EodSession} carries two large text
     * columns this had no reason to pull in on every cash-count load). LAZY is safe for the same
     * open-in-view/@Transactional reasoning. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "eod_session_id", nullable = false, unique = true)
    private EodSession eodSession;

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    private String denominationBreakdownJson;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal physicalTotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedTotal;

    /** {@code physicalTotal - expectedTotal}. Positive = drawer has more than expected. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal variance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private CashCountStatus status;

    @ManyToOne
    @JoinColumn(name = "counted_by")
    private AppUser countedBy;

    private LocalDateTime countedAt;

    // ---- HIGH_VARIANCE_FLAGGED override (F1.2/F1.4/NFR-4 - Level-3 PIN step-up) ----

    @ManyToOne
    @JoinColumn(name = "override_by")
    private AppUser overrideBy;

    @Column(length = 1000)
    private String overrideReason;

    private LocalDateTime overriddenAt;
}
