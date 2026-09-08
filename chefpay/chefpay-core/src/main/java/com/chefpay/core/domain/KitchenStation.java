package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A physical/logical prep station (Grill, Cold/Salad, Beverages, Dessert...) that {@link MenuItem}
 * lines can be routed to, per Phase 3 "Kitchen Stations" (requirement §16/§58). Optional by
 * design: a small kitchen with a single screen can leave every {@code MenuItem.station} null and
 * the kitchen queue just shows one unfiltered ticket stream.
 */
@Entity
@Table(name = "kitchen_station")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class KitchenStation extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String name;

    @Builder.Default
    private int displayOrder = 0;

    @Builder.Default
    private boolean active = true;
}
