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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One ingestion row per channel (POS / a payment gateway / an aggregator) within an
 * {@link EodSession} - AI Backbone Addendum F1.1. {@code POS} is always present and auto-succeeds
 * (it just sums this restaurant's own payment rows for the business date - nothing to "ingest"
 * from outside). A row per enabled aggregator ({@code Restaurant#onlineOrderZomatoEnabled}/
 * {@code onlineOrderSwiggyEnabled}) is created PENDING and waits on a manually-entered
 * {@link AggregatorSettlement} - see that entity's javadoc for why this is manual-entry rather than
 * a live API pull in this round.
 */
@Entity
@Table(name = "channel_ingestion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class ChannelIngestion extends BaseEntity {

    /** Round 27 (pre-deployment audit) fix: same EAGER-by-default trap as {@code Anomaly#eodSession}
     * - see that field's javadoc. LAZY is safe for the same open-in-view/@Transactional reasoning. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "eod_session_id", nullable = false)
    private EodSession eodSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType channelType;

    /** Display name, e.g. "POS", "Razorpay", "Zomato", "Swiggy". */
    @Column(nullable = false, length = 100)
    private String channelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ChannelIngestionStatus status = ChannelIngestionStatus.PENDING;

    /** POS: sum of that day's non-voided payments. Aggregator/gateway: the manually-entered
     * settlement total once submitted (also mirrored onto {@link AggregatorSettlement}). */
    @Column(precision = 12, scale = 2)
    private BigDecimal amountReported;

    @Column(length = 1000)
    private String message;

    private LocalDateTime ingestedAt;
}
