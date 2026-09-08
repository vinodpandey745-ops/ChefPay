package com.chefpay.server.reservations;

import com.chefpay.core.domain.Area;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Reservation;
import com.chefpay.core.domain.ReservationStatus;
import com.chefpay.core.domain.RestaurantTable;
import com.chefpay.core.repository.AreaRepository;
import com.chefpay.core.repository.ReservationRepository;
import com.chefpay.core.repository.RestaurantTableRepository;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bistrodesk Phase 8 (requirement #24/#25): {@code ReservationController} previously had no service
 * layer at all - capacity checking and table-blocking/overlap detection are both genuinely new
 * behavior, not a refactor of existing logic, so this is a fresh {@code @Service} rather than an
 * extraction like {@code TaxCalculationService} was.
 *
 * <p>Both checks key off the same idea: a reservation "holds" its table for the window
 * {@code [reservedFor, reservedFor + durationMinutes)}. {@link Reservation} only ever stores the
 * start instant, so every method here resolves the effective duration via
 * {@link #resolveDurationMinutes(Reservation)} - a per-reservation override if set, else the
 * booking's restaurant-wide default, else a last-resort in-code constant for the edge case of a
 * reservation with no resolvable branch at all (see that method's own javadoc).
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    /** Only reached if a reservation's {@link Reservation#getEffectiveBranch()} is null (a legacy
     * table-less, branch-less row from before Bistrodesk Phase 2) - every real, checkable booking
     * has a branch and therefore a restaurant to read the real default from. */
    private static final int FALLBACK_DURATION_MINUTES = 90;

    /** Statuses that actually hold a table right now - {@code CANCELLED}/{@code NO_SHOW} release it,
     * so they never block a new booking on the same table/area. */
    private static final Set<ReservationStatus> BLOCKING_STATUSES =
            EnumSet.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.SEATED);

    private final ReservationRepository reservationRepository;
    private final RestaurantTableRepository tableRepository;
    private final AreaRepository areaRepository;

    /** The effective hold duration for an already-persisted (or about-to-be-built) reservation:
     * its own override if set, else its branch's restaurant-level default, else the last-resort
     * constant above. */
    public int resolveDurationMinutes(Reservation reservation) {
        return resolveDurationMinutes(reservation.getDurationMinutes(), reservation.getEffectiveBranch());
    }

    /** Same resolution chain, for a request that hasn't been turned into a {@link Reservation}
     * entity yet (the create flow already has the resolved {@link Branch} in hand before it builds
     * one). */
    public int resolveDurationMinutes(Integer override, Branch branch) {
        if (override != null) {
            return override;
        }
        if (branch != null && branch.getRestaurant() != null) {
            return branch.getRestaurant().getDefaultReservationDurationMinutes();
        }
        return FALLBACK_DURATION_MINUTES;
    }

    /** Table-blocking / overlap check (requirement #25): rejects assigning {@code table} to a
     * booking for {@code [start, start+durationMinutes)} if that window overlaps any other active
     * reservation already on that table. {@code excludeReservationId} lets an update re-validate a
     * reservation against every other booking without tripping over its own still-in-place row. */
    public void assertTableAvailable(RestaurantTable table, LocalDateTime start, int durationMinutes, UUID excludeReservationId) {
        LocalDateTime end = start.plusMinutes(durationMinutes);
        List<Reservation> onTable = reservationRepository.findByTable_IdInAndStatusIn(List.of(table.getId()), BLOCKING_STATUSES);
        for (Reservation existing : onTable) {
            if (excludeReservationId != null && excludeReservationId.equals(existing.getId())) {
                continue;
            }
            LocalDateTime existingStart = existing.getReservedFor();
            LocalDateTime existingEnd = existingStart.plusMinutes(resolveDurationMinutes(existing));
            if (start.isBefore(existingEnd) && existingStart.isBefore(end)) {
                throw ApiException.badRequest("TABLE_ALREADY_BOOKED",
                        "Table " + table.getName() + " is already booked from " + existingStart.toLocalTime()
                                + " to " + existingEnd.toLocalTime() + ", which overlaps this reservation's time.");
            }
        }
    }

    /** Capacity check (requirement #24): every active area configured for {@code branch}, with its
     * total and currently-free seating capacity for the requested window, and whether that free
     * capacity is enough for {@code partySize}. Ordered the same way the Areas admin screen orders
     * them ({@code displayOrder}). */
    public List<AreaAvailabilityDto> listAreaAvailability(Branch branch, LocalDateTime start, int durationMinutes,
                                                           int partySize, UUID excludeReservationId) {
        return areaRepository.findByBranchIdAndActiveTrueOrderByDisplayOrderAsc(branch.getId()).stream()
                .map(Area::getName)
                .map(name -> computeAvailability(branch, name, start, durationMinutes, partySize, excludeReservationId))
                .toList();
    }

    /** Enforces the capacity check at booking time: if {@code areaName} can't seat {@code partySize}
     * for this window, throws with the other configured areas that currently CAN - so the host gets
     * an immediate, actionable alternative instead of a dead end. A no-op if the area has room. */
    public void assertAreaCapacity(Branch branch, String areaName, LocalDateTime start, int durationMinutes,
                                    int partySize, UUID excludeReservationId) {
        AreaAvailabilityDto requested = computeAvailability(branch, areaName, start, durationMinutes, partySize, excludeReservationId);
        if (requested.sufficient()) {
            return;
        }
        List<AreaAvailabilityDto> alternatives = listAreaAvailability(branch, start, durationMinutes, partySize, excludeReservationId)
                .stream()
                .filter(a -> !a.areaName().equalsIgnoreCase(areaName) && a.sufficient())
                .toList();
        String suggestion = alternatives.isEmpty()
                ? "No configured area currently has room for a party of " + partySize + " at this time."
                : "Try: " + alternatives.stream()
                        .map(a -> a.areaName() + " (" + a.freeCapacity() + " seat(s) free)")
                        .collect(Collectors.joining(", ")) + ".";
        throw ApiException.badRequest("AREA_AT_CAPACITY",
                areaName + " only has " + requested.freeCapacity() + " seat(s) free for this time, not enough for a party of "
                        + partySize + ". " + suggestion);
    }

    private AreaAvailabilityDto computeAvailability(Branch branch, String areaName, LocalDateTime start, int durationMinutes,
                                                     int partySize, UUID excludeReservationId) {
        List<RestaurantTable> tables = tableRepository.findByFloor_Branch_IdAndSectionIgnoreCaseAndActiveTrue(branch.getId(), areaName);
        int total = tables.stream().mapToInt(RestaurantTable::getSeatingCapacity).sum();
        if (tables.isEmpty()) {
            return new AreaAvailabilityDto(areaName, 0, 0, partySize <= 0);
        }
        List<UUID> tableIds = tables.stream().map(RestaurantTable::getId).toList();
        List<Reservation> blocking = reservationRepository.findByTable_IdInAndStatusIn(tableIds, BLOCKING_STATUSES);
        LocalDateTime end = start.plusMinutes(durationMinutes);
        Set<UUID> blockedTableIds = new HashSet<>();
        for (Reservation r : blocking) {
            if (excludeReservationId != null && excludeReservationId.equals(r.getId())) {
                continue;
            }
            if (r.getTable() == null) {
                continue;
            }
            LocalDateTime rStart = r.getReservedFor();
            LocalDateTime rEnd = rStart.plusMinutes(resolveDurationMinutes(r));
            if (start.isBefore(rEnd) && rStart.isBefore(end)) {
                blockedTableIds.add(r.getTable().getId());
            }
        }
        int free = tables.stream().filter(t -> !blockedTableIds.contains(t.getId())).mapToInt(RestaurantTable::getSeatingCapacity).sum();
        return new AreaAvailabilityDto(areaName, total, free, free >= partySize);
    }
}
