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

/**
 * A named tax rate (GST, VAT, service tax...) referenced by {@link MenuItem#getTaxCode()} - Phase
 * 4 (requirement's "Tax" line item). At most one active row should have {@code defaultRate=true};
 * {@code BillingService.generateBill} applies that rate to any line whose menu item has a null
 * {@code taxCode} rather than leaving it untaxed by omission. Kept as a flat rate per code rather
 * than slab/bracket rules - the requirement doesn't call for slabs, and adding them later is a
 * additive, non-breaking change to this entity.
 */
@Entity
@Table(name = "tax_rate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Tax extends BaseEntity {

    @Column(nullable = false)
    private String name;

    /** Matches {@link MenuItem#getTaxCode()}. Was unique across the whole install; Bistrodesk Phase
     * 3 scopes uniqueness to (code, branch) instead - see {@link #branch}'s javadoc - so the same
     * code can carry a different rate per branch. */
    @Column(nullable = false)
    private String code;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal ratePercent;

    @Builder.Default
    private boolean active = true;

    /** Applied to lines whose menu item has no explicit {@code taxCode}. At most one GLOBAL
     * (branch-less) row and, separately, at most one row per branch should be true - see
     * {@code BillingService#clearExistingDefaultTax}. */
    @Builder.Default
    private boolean defaultRate = false;

    /** Bistrodesk Phase 3 (requirement #6): null (every pre-existing row, and the default for a new
     * one) means this rate is the GLOBAL default for its {@link #code} - applies at any branch that
     * has no more specific override. Set means this row only applies at that one branch, overriding
     * the global rate of the same {@link #code} for bills at that branch (see {@code
     * BillingService#resolveTax}). Deliberately no third "state" tier - branch-level overrides are
     * enough for this install (state/region-based GST variance isn't needed here); adding one later
     * would be an additive change to {@code resolveTax}, not a rework. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;
}
