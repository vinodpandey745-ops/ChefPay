package com.chefpay.server.reservations;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Reservation;
import com.chefpay.core.domain.ReservationStatus;
import com.chefpay.core.domain.RestaurantTable;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.ReservationRepository;
import com.chefpay.core.repository.RestaurantTableRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Round 15: the Reservations screen's backend - see {@link Reservation}'s javadoc for why this is
 * a standalone booking record rather than reusing {@code TableStatus.RESERVED}. Mirrors {@code
 * CustomerController}'s shape closely (same optimistic-locking / null-means-unchanged conventions
 * used everywhere else in this codebase).
 *
 * <p>Bistrodesk Phase 2: branch resolution mirrors {@code OrderService#resolveOrderBranch} exactly
 * - a linked table's own branch always wins (an explicit, disagreeing {@code branchId} is rejected
 * as {@code BRANCH_MISMATCH} rather than silently overridden); a table-less booking requires an
 * explicit branch, falling back through the caller's default/sole branch and finally "the one
 * branch" for a single-branch install (same {@code BRANCH_REQUIRED} safety net {@code
 * OrderService} uses) - unlike Inventory, a booking is never left in a genuinely ambiguous shared
 * state, since "which branch is this guest's table at" always has one real answer.
 *
 * <p>Bistrodesk Phase 4 (requirement #24): the whole controller also requires the
 * {@code RESERVATIONS} plan feature - see {@link RequiresFeature}'s javadoc.
 *
 * <p>Bistrodesk Phase 8 (requirement #24/#25): capacity checking and table-blocking/overlap
 * detection both live in {@link ReservationService} - this controller resolves the branch/table
 * (as it always has) and hands off to that service for the actual availability math.
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@RequiresFeature("RESERVATIONS")
public class ReservationController {

    private final ReservationRepository reservationRepository;
    private final RestaurantTableRepository tableRepository;
    private final BranchRepository branchRepository;
    private final BranchAccessService branchAccessService;
    private final ReservationService reservationService;

    @GetMapping
    @PreAuthorize("hasAuthority('RESERVATION_VIEW') or hasAuthority('RESERVATION_MANAGE')")
    public ApiResponse<List<ReservationDto>> list(@RequestParam(required = false) Boolean upcomingOnly,
                                                    @RequestParam(required = false) UUID branchId,
                                                    @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> accessible = resolveAccessibleBranchIds(branchId, principal);
        List<Reservation> reservations = Boolean.FALSE.equals(upcomingOnly)
                ? reservationRepository.findAllByOrderByReservedForDesc()
                : reservationRepository.findByReservedForGreaterThanEqualOrderByReservedForAsc(
                        LocalDate.now().atStartOfDay());
        return ApiResponse.ok(reservations.stream()
                .filter(r -> visible(r, accessible))
                .map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('RESERVATION_MANAGE')")
    public ApiResponse<ReservationDto> create(@Valid @RequestBody CreateReservationRequest request,
                                               @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        RestaurantTable table = request.tableId() == null ? null : tableRepository.findById(request.tableId())
                .orElseThrow(() -> ApiException.notFound("Table not found"));
        Branch branch = resolveReservationBranch(table, request.branchId(), requester);
        int durationMinutes = reservationService.resolveDurationMinutes(request.durationMinutes(), branch);
        // Bistrodesk Phase 8: a specific table answers "does this fit" more precisely than an
        // area-wide sum, so the overlap check wins when both a table and an area happen to be sent;
        // an area name only matters for the capacity check when there's no table yet to check
        // directly against.
        if (table != null) {
            reservationService.assertTableAvailable(table, request.reservedFor(), durationMinutes, null);
        } else if (request.areaName() != null && !request.areaName().isBlank()) {
            reservationService.assertAreaCapacity(branch, request.areaName(), request.reservedFor(), durationMinutes,
                    request.partySize(), null);
        }
        Reservation saved = reservationRepository.save(Reservation.builder()
                .customerName(request.customerName())
                .customerPhone(request.customerPhone())
                .partySize(request.partySize())
                .reservedFor(request.reservedFor())
                .durationMinutes(request.durationMinutes())
                .table(table)
                .branch(branch)
                .notes(request.notes())
                .status(ReservationStatus.PENDING)
                .build());
        return ApiResponse.ok(toDto(saved));
    }

    /** Bistrodesk Phase 8 (requirement #24): lets the booking UI check seating availability across
     * every configured area before submitting, using the exact same free-capacity math {@code
     * create}/{@code update} enforce - so a host sees which areas have room instead of guessing and
     * hitting the {@code AREA_AT_CAPACITY} rejection. */
    @GetMapping("/availability")
    @PreAuthorize("hasAuthority('RESERVATION_VIEW') or hasAuthority('RESERVATION_MANAGE')")
    public ApiResponse<List<AreaAvailabilityDto>> availability(@RequestParam(required = false) UUID branchId,
                                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime reservedFor,
                                                                 @RequestParam(defaultValue = "1") int partySize,
                                                                 @RequestParam(required = false) Integer durationMinutes,
                                                                 @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        Branch branch = resolveReservationBranch(null, branchId, requester);
        int resolvedDuration = reservationService.resolveDurationMinutes(durationMinutes, branch);
        return ApiResponse.ok(reservationService.listAreaAvailability(branch, reservedFor, resolvedDuration, partySize, null));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('RESERVATION_MANAGE')")
    @Transactional
    public ApiResponse<ReservationDto> update(@PathVariable UUID id, @RequestBody UpdateReservationRequest request,
                                               @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Reservation not found"));
        Branch currentBranch = reservation.getEffectiveBranch();
        branchAccessService.assertAccess(requester, currentBranch == null ? null : currentBranch.getId());
        if (reservation.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(Reservation.class, id);
        }
        if (request.customerName() != null) reservation.setCustomerName(request.customerName());
        if (request.customerPhone() != null) reservation.setCustomerPhone(request.customerPhone());
        if (request.partySize() != null) reservation.setPartySize(request.partySize());
        if (request.reservedFor() != null) reservation.setReservedFor(request.reservedFor());
        if (request.durationMinutes() != null) reservation.setDurationMinutes(request.durationMinutes());
        if (request.notes() != null) reservation.setNotes(request.notes());
        if (request.clearTable()) {
            // Bistrodesk Phase 2: freeze whatever branch this reservation was actually at before
            // dropping the table link, so it doesn't silently fall back into the shared/unassigned
            // bucket just because the table (its only source of branch info so far) was cleared.
            if (reservation.getBranch() == null && reservation.getTable() != null) {
                reservation.setBranch(reservation.getTable().getFloor().getBranch());
            }
            reservation.setTable(null);
        } else if (request.tableId() != null) {
            RestaurantTable newTable = tableRepository.findById(request.tableId())
                    .orElseThrow(() -> ApiException.notFound("Table not found"));
            Branch newBranch = newTable.getFloor().getBranch();
            branchAccessService.assertAccess(requester, newBranch.getId());
            if (reservation.getBranch() != null && !reservation.getBranch().getId().equals(newBranch.getId())) {
                throw ApiException.badRequest("BRANCH_MISMATCH",
                        "This reservation belongs to a different branch than the selected table.");
            }
            reservation.setTable(newTable);
            reservation.setBranch(newBranch);
        } else if (request.branchId() != null) {
            // Only reached when tableId isn't also being set this call - table wins otherwise.
            branchAccessService.assertAccess(requester, request.branchId());
            reservation.setBranch(branchRepository.findById(request.branchId())
                    .orElseThrow(() -> ApiException.notFound("Branch not found")));
        }
        // Bistrodesk Phase 8 (requirement #25): re-run the table-blocking/overlap check whenever
        // this reservation still has a table after the mutations above - not only when tableId is
        // the field being changed this call, since moving reservedFor/durationMinutes on an
        // already-tabled booking can create exactly the same double-booking risk. By this point
        // reservation already reflects every field this request changed, so the check sees the
        // final, post-update state.
        if (reservation.getTable() != null) {
            reservationService.assertTableAvailable(reservation.getTable(), reservation.getReservedFor(),
                    reservationService.resolveDurationMinutes(reservation), reservation.getId());
        }
        if (request.status() != null) {
            try {
                reservation.setStatus(ReservationStatus.valueOf(request.status()));
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("INVALID_RESERVATION_STATUS", "Unknown reservation status: " + request.status());
            }
        }
        return ApiResponse.ok(toDto(reservationRepository.save(reservation)));
    }

    /** Mirrors {@code OrderService#resolveOrderBranch} exactly: the table's own branch wins when
     * present (a disagreeing explicit branchId is a genuine mismatch, not silently overridden);
     * otherwise the given branchId (access-checked), the caller's default/sole branch, or "the one
     * branch" for a single-branch install - only throwing {@code BRANCH_REQUIRED} when a
     * table-less booking is genuinely ambiguous. */
    private Branch resolveReservationBranch(RestaurantTable table, UUID requestedBranchId, AppUser requester) {
        if (table != null) {
            Branch tableBranch = table.getFloor().getBranch();
            if (requestedBranchId != null && !requestedBranchId.equals(tableBranch.getId())) {
                throw ApiException.badRequest("BRANCH_MISMATCH",
                        "The selected table belongs to a different branch than the one specified.");
            }
            branchAccessService.assertAccess(requester, tableBranch.getId());
            return tableBranch;
        }
        if (requestedBranchId != null) {
            branchAccessService.assertAccess(requester, requestedBranchId);
            return branchRepository.findById(requestedBranchId)
                    .orElseThrow(() -> ApiException.notFound("Branch not found"));
        }
        if (requester != null && requester.getDefaultBranch() != null) {
            return requester.getDefaultBranch();
        }
        if (requester != null && requester.getBranches().size() == 1) {
            return requester.getBranches().iterator().next();
        }
        List<Branch> allBranches = branchRepository.findAll();
        if (allBranches.size() == 1) {
            return allBranches.get(0);
        }
        throw ApiException.badRequest("BRANCH_REQUIRED",
                "Specify which branch this reservation is for (this restaurant has more than one "
                        + "branch and no table was selected to infer it from).");
    }

    private Set<UUID> resolveAccessibleBranchIds(UUID requestedBranchId, AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        if (requestedBranchId != null) {
            branchAccessService.assertAccess(requester, requestedBranchId);
            return Set.of(requestedBranchId);
        }
        return branchAccessService.accessibleBranchIds(requester);
    }

    private boolean visible(Reservation r, Set<UUID> accessibleBranchIds) {
        Branch branch = r.getEffectiveBranch();
        return accessibleBranchIds == null || branch == null || accessibleBranchIds.contains(branch.getId());
    }

    private ReservationDto toDto(Reservation r) {
        int durationMinutes = reservationService.resolveDurationMinutes(r);
        return new ReservationDto(r.getId(), r.getCustomerName(), r.getCustomerPhone(), r.getPartySize(),
                r.getReservedFor(), durationMinutes, r.getDurationMinutes(), r.getReservedFor().plusMinutes(durationMinutes),
                r.getTable() == null ? null : r.getTable().getId(),
                r.getTable() == null ? null : r.getTable().getName(), r.getNotes(), r.getStatus().name(),
                r.getVersion(), r.getEffectiveBranch() == null ? null : r.getEffectiveBranch().getId());
    }
}
