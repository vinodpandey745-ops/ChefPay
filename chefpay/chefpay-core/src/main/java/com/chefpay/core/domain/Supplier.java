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

/**
 * A supplier a restaurant orders inventory from (Round 12 §12-§20). Deliberately a plain contact
 * record today - no API credentials, endpoint URL, or integration-specific fields live here. The
 * requirement's "supplier-abstraction interface for future API integration ... not tightly
 * coupled" is met at the service layer instead: {@code SupplierChannel} is the pluggable seam
 * (see its javadoc) a future API-backed supplier would implement, while this entity only ever
 * grows the offline/contact fields every supplier needs regardless of how it's eventually reached.
 */
@Entity
@Table(name = "supplier")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Supplier extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String contactPerson;

    private String phone;

    private String email;

    @Column(length = 500)
    private String address;

    @Column(length = 1000)
    private String notes;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** Bistrodesk branch-isolation release (requirement #6): a supplier created for one branch must
     * not automatically appear for another - same reversal of the prior "shared directory" design
     * and same nullable-during-migration convention as {@link Customer#getBranch()} (mirrors {@link
     * InventoryItem#getBranch()}'s shape exactly). Every create path resolves and sets one (see
     * {@code SupplierController#create}); every pre-existing branchless row is backfilled onto the
     * install's first branch at startup (see {@code DataSeeder}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;
}
