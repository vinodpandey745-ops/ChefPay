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

/**
 * A reusable discount preset (e.g. "Staff Meal 50%", "₹100 Off Coupon") staff can apply to an
 * order's bill - Phase 4 (requirement's "Discount"). Applying ANY discount, preset or a one-off
 * manual amount, requires {@code DISCOUNT_APPROVE} (see {@code BillingService.applyDiscount}) -
 * this entity is just the catalog of common presets, not itself a grant of permission to use one.
 */
@Entity
@Table(name = "discount")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Discount extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType type;

    /** Percent (0-100) if {@code type == PERCENTAGE}, currency amount if {@code FIXED_AMOUNT}. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal value;

    /** Round 12: caps how much currency this preset can ever waive on a single bill, regardless of
     * how large the type/value calculation comes out to - e.g. "20% off, capped at ₹200" so a
     * PERCENTAGE preset never blows past a sane ceiling on a big order. Null = no cap (today's
     * unbounded behavior, unchanged for every existing preset). */
    @Column(precision = 12, scale = 2)
    private BigDecimal maxDiscountAmount;

    /** Round 12: when set, this preset only discounts line items in this one category (e.g. a
     * "Beverages 10% Off" preset never touches food items) rather than the whole-bill subtotal.
     * Null = today's behavior (applies to the whole order's subtotal). See
     * {@code BillingService#applyDiscount}'s category-scoped branch. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applicable_category_id")
    private MenuCategory applicableCategory;

    @Builder.Default
    private boolean active = true;

    /** Bistrodesk Phase 3 (requirement #6): null (every pre-existing preset, and the default for a
     * new one) means this preset is shared - usable at every branch, exactly today's only behavior.
     * Set means it's specific to one branch; {@code BillingService#applyDiscount} rejects using it
     * on an order whose branch doesn't match (see that method's javadoc) so a Branch-A-only
     * promotion can't be applied to a Branch-B bill. Unlike {@link Tax}, there's no code-based
     * lookup/override concept here - a discount is picked by id, not resolved by a shared key - so
     * this is pure visibility/eligibility scoping, not a global-vs-branch precedence resolution. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;
}
