package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Editable threshold(s) for one {@link FraudRuleCode} (AI Backbone Addendum F1.5 - "Store rule
 * thresholds in a database-backed rule_config table, editable from an admin screen, rather than
 * hard-coding them... this is the single highest-leverage change vs. the reference design"). One
 * row per rule code, seeded idempotently on startup (see {@code DataSeeder}-style upsert) with the
 * MVP defaults from the requirements doc's own rule table, then only ever edited via
 * {@code RULE_CONFIG_MANAGE}, never re-overwritten by a later startup.
 *
 * <p>Two numeric slots ({@link #thresholdValue}/{@link #secondaryThresholdValue}) cover every
 * MVP rule: most need only one number (a count, a percent, a currency amount); MANAGER_PIN_OVERUSE
 * needs both (a void count AND a same-minute-cross-terminal check has no second number, so that
 * half is a fixed rule, not configurable - only the void-count threshold uses this row).
 * {@link #windowMinutes} covers the rolling-window rules (NO_SALE_FREQUENCY's default 60-minute
 * window); rules that don't use a window (e.g. per-business-date rules) simply ignore it.
 */
@Entity
@Table(name = "rule_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class RuleConfig extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 40)
    private FraudRuleCode ruleCode;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal thresholdValue;

    @Column(precision = 12, scale = 2)
    private BigDecimal secondaryThresholdValue;

    private Integer windowMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnomalySeverity severity;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 500)
    private String description;
}
