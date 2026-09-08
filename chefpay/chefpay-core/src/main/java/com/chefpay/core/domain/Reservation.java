package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDateTime;

/**
 * Round 15: a standalone booking record - guest name/phone, party size, requested date/time,
 * optional linked table (for planning; does not itself change {@link RestaurantTable#getStatus()}
 * - see {@link ReservationStatus}'s javadoc), and a status lifecycle staff walk through as the
 * booking is confirmed, the guest is seated, or it falls through (cancelled/no-show). Deliberately
 * NOT foreign-keyed to {@link Customer} the way {@link Order#customerName}/{@code customerPhone}
 * aren't either - same lightweight inline-capture rationale as that entity's own javadoc, so a
 * reservation can be taken over the phone for someone not yet in the guest directory.
 */
@Entity
@Table(name = "reservation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Reservation extends BaseEntity {

    @Column(nullable = false)
    private String customerName;

    private String customerPhone;

    @Column(nullable = false)
    private int partySize;

    @Column(nullable = false)
    private LocalDateTime reservedFor;

    @ManyToOne
    @JoinColumn(name = "table_id")
    private RestaurantTable table;

    @Column(length = 1000)
    private String notes;

    /** Bistrodesk Phase 8 (requirement #25): optional override of how long this specific booking
     * holds its table, in minutes, for the table-blocking/overlap check - null means "use {@link
     * Restaurant#getDefaultReservationDurationMinutes()}" (see {@code ReservationService
     * #resolveDurationMinutes}). Most bookings never set this; it exists for the outlier (a long
     * birthday party, a private event) that genuinely needs a longer or shorter hold than the
     * restaurant's normal turnaround default. */
    private Integer durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    /** Bistrodesk Phase 2: which branch this booking is taken for. Stored directly (rather than
     * only derived from {@link #table}) because {@link #table} is itself optional - a phone
     * reservation may have no table assigned yet - so the branch has to be capturable
     * independently. Once a table IS linked, "table wins": {@code ReservationController} keeps
     * this in sync with the table's own branch and rejects a disagreeing explicit branchId, the
     * same convention {@code Order#branch}/{@code OrderService} already use. See
     * {@link #getEffectiveBranch()} for the read-side fallback covering rows from before this
     * column existed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** Resolves the branch this reservation belongs to: the direct {@link #branch} if set,
     * otherwise (a pre-Bistrodesk-Phase-2 row with a table) the table's own floor's branch -
     * mirrors {@code Order#getEffectiveBranch()} exactly. Returns {@code null} only for a legacy
     * table-less reservation that predates this column - the shared/unrestricted bucket, same
     * convention used throughout Bistrodesk Phase 2. */
    public Branch getEffectiveBranch() {
        if (branch != null) {
            return branch;
        }
        return table == null ? null : table.getFloor().getBranch();
    }
}
