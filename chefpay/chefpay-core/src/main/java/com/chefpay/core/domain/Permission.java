package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A single grantable capability, e.g. {@code ORDER_CREATE}, {@code DISCOUNT_APPROVE},
 * {@code REPORT_VIEW}. Permission codes are seeded as data (see Flyway seed migration), not
 * hardcoded into endpoint annotations beyond the code string itself - so an admin can regroup
 * which roles get which permission without a code change.
 */
@Entity
@Table(name = "permission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Permission extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    private String description;
}
