package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A manager-entered settlement figure for one aggregator on one business date (AI Backbone
 * Addendum F1.1). Bound to the corresponding {@link ChannelIngestion} row.
 *
 * <p><b>Why manual entry, not a live API pull:</b> the requirements doc's own F1.1 technical note
 * allows for "scheduled report downloads where a live API is unavailable" - that is the case here.
 * ChefPay has no Zomato/Swiggy/payment-gateway API credentials or webhook receiver today (see
 * {@code Restaurant#onlineOrderZomatoEnabled}'s javadoc - those toggles are display-only, nothing
 * reads them to gate a real order feed), and {@code Order} carries no field distinguishing which
 * aggregator an {@code ONLINE_ORDER} came from, so a true per-order match against a live settlement
 * feed isn't buildable against today's data model regardless of API access. Manual entry of both
 * sides (what the POS shows for that channel, what the aggregator's settlement report shows) is a
 * real, usable Phase-1 path - the {@code AggregatorReconciliationProvider} interface this could
 * plug a live integration behind later lives in the Round 13 report's deferred-work section, not in
 * code, since there is no real aggregator credential to build and test it against.
 */
@Entity
@Table(name = "aggregator_settlement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class AggregatorSettlement extends BaseEntity {

    /** Round 27 (pre-deployment audit) fix: same EAGER-by-default trap as {@code Anomaly#eodSession}
     * - see that field's javadoc. Was compounded here by also being loaded a second way via the
     * (also now-fixed) {@link #channelIngestion} chain. LAZY is safe for the same
     * open-in-view/@Transactional reasoning. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "eod_session_id", nullable = false)
    private EodSession eodSession;

    /** Round 27 (pre-deployment audit) fix: {@link ChannelIngestion} itself is small, but this was
     * EAGER by default, and (before this round's fix) so was its own {@code eodSession} - meaning a
     * single {@code AggregatorSettlement} load pulled in {@link EodSession}'s large text columns
     * twice, once directly via {@link #eodSession} and once through this chain. LAZY here as well,
     * both to close that second path and for consistency with every other cross-entity association
     * fixed this round. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_ingestion_id", nullable = false)
    private ChannelIngestion channelIngestion;

    @Column(nullable = false, length = 100)
    private String aggregatorName;

    /** What the POS/manager believes this channel's orders were worth for the day. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal posRecordedTotal;

    /** What the aggregator's settlement report/portal shows was actually paid out (before or after
     * commission, per {@link #commissionAmount} - whichever the manager was looking at). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal reportedSettlementTotal;

    @Column(precision = 12, scale = 2)
    private BigDecimal commissionAmount;

    /** {@code (posRecordedTotal - reportedSettlementTotal) / posRecordedTotal * 100}, signed. */
    @Column(precision = 7, scale = 2)
    private BigDecimal variancePercent;

    @Builder.Default
    private boolean flagged = false;

    @Column(length = 1000)
    private String notes;

    @ManyToOne
    @JoinColumn(name = "entered_by")
    private AppUser enteredBy;

    private LocalDateTime enteredAt;
}
