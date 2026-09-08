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

/**
 * A reusable preset instruction/note (e.g. "Extra Spicy", "No Onion", "Less Sugar") staff can
 * quick-pick for {@link OrderItem#getSpecialInstructions()} instead of free-typing it every time -
 * Round 8. Just the catalog of common presets, same role for special instructions that
 * {@link Discount} plays for the bill: picking one doesn't stop a waiter from still typing a
 * one-off note by hand.
 */
@Entity
@Table(name = "special_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class SpecialNote extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(nullable = false)
    private String text;

    @Builder.Default
    @Column(nullable = false)
    private int displayOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
