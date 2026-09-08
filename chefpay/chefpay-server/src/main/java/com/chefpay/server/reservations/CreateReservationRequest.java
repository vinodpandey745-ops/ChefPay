package com.chefpay.server.reservations;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;
import java.util.UUID;

/** {@code branchId} (Bistrodesk Phase 2): required when {@code tableId} is omitted (a phone
 * booking with no table yet has nothing else to derive a branch from) - null is fine when
 * {@code tableId} is set (the table's own branch wins - see {@code ReservationController}'s
 * javadoc) or on a single-branch install (falls back to "the one branch").
 *
 * <p>Bistrodesk Phase 8 (requirement #24/#25): {@code areaName}, when {@code tableId} is omitted,
 * runs the capacity check against that {@link com.chefpay.core.domain.Area} (rejecting with other
 * areas that currently have room, if it doesn't) - ignored when {@code tableId} is set, since a
 * specific table already answers "does this fit" more precisely than an area-wide sum does; that
 * case instead runs the table-blocking/overlap check. {@code durationMinutes}, if given, overrides
 * this one booking's hold length for both checks - null uses the restaurant's configured default
 * (see {@code ReservationService#resolveDurationMinutes}). Both fields are optional: a booking with
 * neither a table nor an area (the pre-Phase-8 default - a phone reservation nothing's been
 * assigned to yet) skips both checks entirely, same as before this phase existed. */
public record CreateReservationRequest(
        @NotBlank String customerName,
        String customerPhone,
        @Positive int partySize,
        @NotNull LocalDateTime reservedFor,
        UUID tableId,
        UUID branchId,
        String areaName,
        Integer durationMinutes,
        String notes
) {
}
