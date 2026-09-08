package com.chefpay.core.repository;

import com.chefpay.core.domain.Reservation;
import com.chefpay.core.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** Backs the Reservations screen's default view: everything from a given moment forward, soonest
     * first - deliberately not filtered by status here, the client filters PENDING/CONFIRMED/etc. so
     * a manager can still see e.g. today's cancellations for reference. */
    List<Reservation> findByReservedForGreaterThanEqualOrderByReservedForAsc(LocalDateTime from);

    List<Reservation> findAllByOrderByReservedForDesc();

    /** Bistrodesk Phase 8 (requirement #25): the candidate set for the table-blocking/overlap check
     * - every reservation on any of these tables that's still in a status that actually holds the
     * table ({@code statuses}, see {@code ReservationService#BLOCKING_STATUSES}). Callers do the
     * actual [start, end) interval-overlap math in Java rather than pushing a datetime-arithmetic
     * WHERE clause into the query, since the effective end instant depends on each row's own
     * possibly-null {@code durationMinutes} override - not something a single SQL predicate can
     * express without duplicating {@code ReservationService}'s resolution logic in SQL. */
    List<Reservation> findByTable_IdInAndStatusIn(Collection<UUID> tableIds, Collection<ReservationStatus> statuses);
}
