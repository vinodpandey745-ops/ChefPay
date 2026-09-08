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
 * A named seating section/zone within a branch (e.g. "Indoor", "Patio", "AC Hall") - Round 8. A
 * sibling of {@link Floor}: where Floor groups {@link RestaurantTable}s by physical level, Area is
 * the catalog a UI picker is built from to populate {@code RestaurantTable#section}'s existing
 * free-text field, rather than a new relation on the table itself. Deliberately kept to physical
 * sections only; this app already has a plain on/off per aggregator on {@link Restaurant} (see
 * {@code onlineOrderZomatoEnabled}/{@code onlineOrderSwiggyEnabled}) for the "delivery channel as
 * pseudo-area" idea some commercial POS products fold into the same list.
 */
@Entity
@Table(name = "area")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Area extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(nullable = false)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private int displayOrder = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
