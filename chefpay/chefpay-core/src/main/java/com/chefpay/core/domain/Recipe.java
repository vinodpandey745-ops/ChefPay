package com.chefpay.core.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 14 (AI Backbone Addendum §5, F2.1) - the bill-of-materials link {@link MenuItem}'s own
 * javadoc explicitly deferred ("Modifier groups and recipe/inventory links attach to this entity
 * starting Phase 5... kept out for now"). One {@link MenuItem} has at most one {@code Recipe}
 * ({@code menuItem} is unique) rather than a recipe-per-portion/variant scheme - a menu item with
 * a Full/Half price split ({@link MenuItem#getHalfPrice()}) uses the SAME recipe scaled by however
 * many "servings" a given {@link com.chefpay.core.domain.OrderItem#getQuantity()} represents; a
 * true per-portion BOM (different ingredient lines for Half vs Full) is a real refinement but out
 * of scope for this round - see the round's report for why.
 *
 * <p>Deliberately NOT every {@link MenuItem} needs one: {@code RecipeService#findByMenuItem}
 * returning empty is the normal, expected case for any item nobody has costed out yet (drinks
 * poured from a bottle already tracked 1:1 via {@code directSale} style items, or simply an item
 * whose recipe hasn't been entered), and every caller (stock deduction on send-to-kitchen, Menu
 * Engineering Matrix, Dynamic Pricing) treats "no recipe" as "skip this item" rather than an error.
 */
@Entity
@Table(name = "recipe")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Recipe extends BaseEntity {

    @OneToOne(optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false, unique = true)
    private MenuItem menuItem;

    /** How many servings one full run of {@link #lines} produces - almost always 1 (the common
     * "these ingredients make one plate" case); kept as a divisor rather than hardcoded to 1 so a
     * batch-prepared recipe (e.g. a sauce base portioned into 4 servings) can still be costed
     * accurately without duplicating lines four times. */
    @Builder.Default
    @Column(nullable = false)
    private int servingsPerBatch = 1;

    @Builder.Default
    private boolean active = true;

    @Column(length = 1000)
    private String notes;

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt asc")
    @Builder.Default
    private List<RecipeLine> lines = new ArrayList<>();
}
