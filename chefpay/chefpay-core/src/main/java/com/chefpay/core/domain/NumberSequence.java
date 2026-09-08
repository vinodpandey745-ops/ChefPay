package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Backs concurrency-safe number generation (requirement §57) for order/invoice/payment/refund
 * numbers - one row per series (e.g. "ORDER", "INVOICE"), incremented under a pessimistic write
 * lock by {@code NumberGeneratorService} so two terminals creating an order in the same
 * millisecond never get the same number.
 */
@Entity
@Table(name = "number_sequence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NumberSequence {

    @Id
    private String seriesKey;

    @Column(nullable = false)
    private long currentValue;
}
