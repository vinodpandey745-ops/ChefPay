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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Round 14 (F3.2) - "advisory dynamic pricing suggestions": {@code PriceSuggestionService} raises
 * one of these when a {@link MenuItem}'s recipe cost has risen enough to erode its margin below
 * {@code Restaurant#getMarginErosionThresholdPercent()}. Deliberately advisory only, matching the
 * requirement's own "advisory, human-approved" framing (never SplitCheck-style auto-write to
 * {@code MenuItem.price}) - see {@code PriceSuggestionService}'s class javadoc for the full
 * reasoning, which mirrors {@code ReplenishmentService}'s "never auto-creates/sends a PO without
 * authorization" stance for the same category of "AI/rule suggests, a human decides" feature.
 */
@Entity
@Table(name = "price_change_suggestion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class PriceChangeSuggestion extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal currentPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal currentRecipeCost;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal currentMarginPercent;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal suggestedPrice;

    /** What the margin would be at {@link #suggestedPrice} given {@link #currentRecipeCost} -
     * always recomputed to exactly hit the restaurant's configured target, not just "a bit more." */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal projectedMarginPercent;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private PriceChangeSuggestionStatus status = PriceChangeSuggestionStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime detectedAt;

    @ManyToOne
    @JoinColumn(name = "decided_by")
    private AppUser decidedBy;

    private LocalDateTime decidedAt;

    @Column(length = 1000)
    private String decisionNote;
}
