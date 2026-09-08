package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * One ingredient line of a {@link Recipe} (Round 14, F2.1) - "{@code quantityPerBatch} of this
 * {@link InventoryItem}, in the inventory item's own {@code unit}, makes {@code
 * Recipe#servingsPerBatch} servings." No unit-conversion engine exists in this codebase (see
 * {@code InventoryItem#getUnit()}'s javadoc) - whoever enters a recipe line is responsible for
 * using the SAME unit the inventory item is already tracked in (e.g. if Basmati Rice is tracked in
 * "kg", a recipe line reading "0.150" means 150 grams, not 150 kg mislabeled).
 */
@Entity
@Table(name = "recipe_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class RecipeLine extends BaseEntity {

    /** Bistrodesk fix (production {@code StackOverflowError} class - see {@code
     * Device#lastUser}'s javadoc for the full explanation): {@link Recipe#lines} is the inverse
     * side of this relationship - without this exclusion, hashing this line would walk back into
     * its parent recipe's line list and re-hash every sibling line forever. */
    @EqualsAndHashCode.Exclude
    @ManyToOne(optional = false)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @ManyToOne(optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantityPerBatch;
}
