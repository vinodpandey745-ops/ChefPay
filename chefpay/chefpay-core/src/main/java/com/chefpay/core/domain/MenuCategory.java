package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Entity
@Table(name = "menu_category")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class MenuCategory extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Builder.Default
    private int displayOrder = 0;

    @Builder.Default
    private boolean active = true;

    /** Round 18: an optional parent category - e.g. "Veg"/"Non-Veg" as subcategories of "Main
     * Course" - so two near-duplicate categories a manager wants to keep visually distinct can be
     * organized as a hierarchy instead of either staying flat siblings or being force-merged into
     * one. Null (the default, and every pre-Round-18 row) = a normal top-level category, unchanged
     * behavior. Deliberately capped at one level deep (a subcategory may not itself have a parent -
     * enforced in {@code MenuController}) - this is meant to solve "Main Course > Veg/Non-Veg", not
     * become a general-purpose arbitrary-depth taxonomy. */
    @ManyToOne
    @JoinColumn(name = "parent_category_id")
    private MenuCategory parentCategory;
}
