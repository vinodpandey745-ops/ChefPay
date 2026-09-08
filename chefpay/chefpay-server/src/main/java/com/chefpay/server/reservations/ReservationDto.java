package com.chefpay.server.reservations;

import java.time.LocalDateTime;
import java.util.UUID;

/** Bistrodesk Phase 8 (requirement #25): {@code durationMinutes} is always the *resolved* effective
 * value (the reservation's own override if it has one, else the restaurant default) - never null
 * here even though the underlying column can be, so the UI can show a time range without also
 * needing to know the restaurant's default separately. {@code reservedUntil} is simply {@code
 * reservedFor + durationMinutes}, precomputed so the client never re-derives the same arithmetic
 * {@code ReservationService} already did to produce {@code durationMinutes}.
 *
 * <p>{@code durationOverrideMinutes} is the raw, possibly-null column underneath that resolution -
 * kept separate specifically so an edit form can tell "this booking has its own explicit override"
 * apart from "this is just today's restaurant default" and prefill accordingly. Using the resolved
 * {@code durationMinutes} for that would silently turn every edited reservation into an explicit
 * override the moment it's saved, even if nobody touched the duration field - pinning it to
 * whatever the default happened to be at edit time instead of continuing to track the restaurant's
 * default going forward. */
public record ReservationDto(
        UUID id,
        String customerName,
        String customerPhone,
        int partySize,
        LocalDateTime reservedFor,
        int durationMinutes,
        Integer durationOverrideMinutes,
        LocalDateTime reservedUntil,
        UUID tableId,
        String tableName,
        String notes,
        String status,
        long version,
        UUID branchId
) {
}
