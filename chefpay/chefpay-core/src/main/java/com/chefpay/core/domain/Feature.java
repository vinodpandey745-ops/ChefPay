package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Phase 2: a single gate-able capability (e.g. {@code ADVANCED_REPORTS}, {@code INVENTORY},
 * {@code MULTI_BRANCH}) a {@link SubscriptionPlan} either includes or doesn't - presence in that
 * plan's {@link SubscriptionPlan#getFeatures()} join table means enabled, absence means disabled.
 * Deliberately mirrors {@link Permission}'s shape exactly (a plain unique {@code code} +
 * description, seeded as data) - same "an admin can regroup entitlements without a code change"
 * reasoning, applied to plan features instead of role permissions. Seeded/extended by {@code
 * DataSeeder}, never hardcoded into a screen's own conditional.
 */
@Entity
@Table(name = "feature")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Feature extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    private String description;
}
