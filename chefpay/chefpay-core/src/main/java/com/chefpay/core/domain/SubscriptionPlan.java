package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 2: a configurable plan the platform owner (ChefPay, not the restaurant) defines - name,
 * duration, price, GST, optional per-plan limits, and which {@link Feature}s it includes. Nothing
 * about a plan's shape is hardcoded into any screen; every plan a restaurant sees on its
 * Subscription page (item 25 of the request) comes from rows here, writable only through {@code
 * PlatformOwnerController} - never through any endpoint the restaurant's own Owner/Admin can reach,
 * matching the "centralized, platform-owner-controlled" requirement even under the confirmed
 * one-deployment-per-business hosting model (see PHASE2_ORG_SUBSCRIPTION_DESIGN.md's Gap
 * Analysis for why "centralized" doesn't mean "one shared database" here).
 */
@Entity
@Table(name = "subscription_plan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class SubscriptionPlan extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    /** e.g. 30/60/90/180/365 - fully configurable, never a hardcoded "trial = 1 month" assumption
     * anywhere else in the codebase (item 17 of the request). */
    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercent = BigDecimal.ZERO;

    /** Null = unlimited. Enforced by {@code EntitlementService}, not the database - a limit is a
     * business rule the platform owner can raise/lower per plan, not a hard schema constraint. */
    @Column(name = "max_branches")
    private Integer maxBranches;

    @Column(name = "max_terminals")
    private Integer maxTerminals;

    @Column(name = "max_users")
    private Integer maxUsers;

    /** Marks this plan as the free/trial tier - informational (drives the Subscription screen's
     * "Free Trial" label) rather than behaviorally special; a trial is otherwise just a plan with
     * price=0 like any other, so extending/changing trial length is the same "edit a plan" action
     * as any paid plan (item 18). */
    @Builder.Default
    @Column(name = "is_trial", nullable = false)
    private boolean trial = false;

    /** An inactive plan stays visible on any subscription already using it, but is not offered as
     * a choice for a new/renewed subscription - same soft-delete convention as everywhere else in
     * this codebase, applied to a plan the platform owner has retired. */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "plan_feature",
            joinColumns = @JoinColumn(name = "subscription_plan_id"),
            inverseJoinColumns = @JoinColumn(name = "feature_id"))
    @Builder.Default
    private Set<Feature> features = new HashSet<>();
}
