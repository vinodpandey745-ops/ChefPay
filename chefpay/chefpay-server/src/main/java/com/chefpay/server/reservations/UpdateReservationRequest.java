package com.chefpay.server.reservations;

import java.time.LocalDateTime;
import java.util.UUID;

/** Any null field (except version) leaves that attribute unchanged. {@code clearTable=true} unsets
 * a previously-linked table (same convention as {@code MenuDtos.UpdateItemRequest#clearStation}).
 * {@code status} (if provided) must be one of {@link com.chefpay.core.domain.ReservationStatus}'s
 * names - validated in the controller alongside the transition. {@code branchId} (Bistrodesk Phase
 * 2) only applies when {@code tableId} isn't also being set this call (the table's branch always
 * wins - see {@code ReservationController}); useful for correcting/assigning a table-less
 * reservation's branch after the fact.
 *
 * <p>Bistrodesk Phase 8 (requirement #25): {@code durationMinutes}, if given, overrides this
 * booking's hold length (null leaves it unchanged, same "null means unchanged" convention as every
 * other field here - there's no way to clear an override back to "use the default" via this field
 * alone, matching {@code clearTable}'s explicit-flag pattern being reserved for genuinely
 * ambiguous nulls; an override is simply replaced with the restaurant default's own value if a
 * caller wants to reset it). Whenever this update leaves the reservation with a table (whether
 * newly assigned this call or already set), {@code ReservationController} re-runs the
 * table-blocking/overlap check with the final resolved time/duration - not just when {@code
 * tableId} is the field being changed this call. */
public record UpdateReservationRequest(
        String customerName,
        String customerPhone,
        Integer partySize,
        LocalDateTime reservedFor,
        UUID tableId,
        boolean clearTable,
        UUID branchId,
        Integer durationMinutes,
        String notes,
        String status,
        long version
) {
}
