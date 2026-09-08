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
 * A named delivery rider on this restaurant's roster (Round 9) - assignable to a {@link
 * Order#getOrderType() DELIVERY}/{@code ONLINE_ORDER} order via {@code Order#deliveryBoy} so
 * front-of-house can track who is out with which order. Deliberately unscoped to a branch (unlike
 * {@link Area}/{@link SpecialNote}): a single small roster is what was asked for, and a branch
 * relation can be added the same way those entities did if a multi-branch deployment ever needs
 * per-branch rosters. The whole feature is gated off by default via {@code
 * Restaurant#deliveryBoyFeatureEnabled} - see that field's javadoc.
 */
@Entity
@Table(name = "delivery_boy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class DeliveryBoy extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String phone;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
