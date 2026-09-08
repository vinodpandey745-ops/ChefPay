package com.chefpay.server.tables;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Floor;
import com.chefpay.core.domain.RestaurantTable;
import com.chefpay.core.domain.TableStatus;
import com.chefpay.core.repository.FloorRepository;
import com.chefpay.core.repository.RestaurantTableRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Visual table matrix CRUD (requirement §10/§11). Status-driving order-lifecycle transitions land in Phase 2. */
@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
public class TableController {

    private final RestaurantTableRepository tableRepository;
    private final FloorRepository floorRepository;
    private final WebSocketEventPublisher eventPublisher;
    private final BranchAccessService branchAccessService;

    /** Round 11: {@code branchId} is optional and scopes the result to one branch of a restaurant
     * CHAIN (multiple physical locations sharing this one deployment) - omitted, this returns every
     * table across every branch exactly like it always has, so a single-location restaurant (the
     * common case, and every deployment before this round) sees zero behavior change. Pass it once
     * {@code ShellView}'s branch switcher shows more than one branch to pick from.
     *
     * <p>Bistrodesk Phase 2: an explicit {@code branchId} is now access-checked, and an omitted one
     * now defaults to the caller's own accessible branches rather than unconditionally "every table"
     * - see {@code OrderController#listOpen}'s identical javadoc for the exact same reasoning and
     * the "{@code null} means unrestricted" convention that keeps a single-branch install unchanged.
     *
     * <p>Bistrodesk branch-isolation release (requirement #7): an omitted {@code branchId} used to
     * fall straight to {@link BranchAccessService#accessibleBranchIds} - for a multi-branch or
     * {@code VIEW_ALL_BRANCHES} caller that merges every branch's tables into one flat, unlabeled
     * list, which is exactly what "tables from different branches appear together" describes for a
     * POS terminal session. Now tries {@link BranchAccessService#resolveEffectiveBranchId} first -
     * the caller's ONE working branch (their configured default, or their single accessible branch,
     * or this install's one-and-only branch) - so a terminal session is narrowed to just its own
     * branch's tables. Only when that's genuinely ambiguous (an unrestricted caller with no default
     * on a multi-branch install - its {@code BRANCH_REQUIRED} error) does this fall back to the
     * previous "every accessible branch" behavior, which is the right shape for a back-office view
     * that intentionally wants to browse tables across branches. No existing single-branch install
     * or already-branch-scoped caller changes behavior. */
    @GetMapping
    @PreAuthorize("hasAuthority('TABLE_VIEW') or hasAuthority('TABLE_MANAGE')")
    public ApiResponse<List<TableDto>> list(@RequestParam(required = false) UUID branchId,
                                             @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        Set<UUID> effectiveBranchIds;
        if (branchId != null) {
            branchAccessService.assertAccess(requester, branchId);
            effectiveBranchIds = Set.of(branchId);
        } else {
            try {
                effectiveBranchIds = Set.of(branchAccessService.resolveEffectiveBranchId(requester, null));
            } catch (ApiException ex) {
                if (!"BRANCH_REQUIRED".equals(ex.getErrorCode())) {
                    throw ex;
                }
                effectiveBranchIds = branchAccessService.accessibleBranchIds(requester);
            }
        }
        List<RestaurantTable> tables;
        if (effectiveBranchIds == null) {
            tables = tableRepository.findByActiveTrueOrderByGridRowAscGridColumnAsc();
        } else if (effectiveBranchIds.size() == 1) {
            tables = tableRepository.findByFloor_Branch_IdAndActiveTrueOrderByGridRowAscGridColumnAsc(effectiveBranchIds.iterator().next());
        } else {
            tables = tableRepository.findByFloor_Branch_IdInAndActiveTrueOrderByGridRowAscGridColumnAsc(effectiveBranchIds);
        }
        return ApiResponse.ok(tables.stream().map(this::toDto).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('TABLE_MANAGE')")
    public ApiResponse<TableDto> create(@Valid @RequestBody CreateTableRequest request,
                                         @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Floor floor = floorRepository.findById(request.floorId()).orElseThrow(() -> ApiException.notFound("Floor not found"));
        // Bistrodesk Phase 2: a caller restricted to specific branches must not be able to create a
        // table on a floor belonging to a branch they can't otherwise access.
        branchAccessService.assertAccess(branchAccessService.resolve(principal), floor.getBranch().getId());
        RestaurantTable table = RestaurantTable.builder()
                .floor(floor)
                .name(request.name())
                .seatingCapacity(request.seatingCapacity())
                .section(request.section())
                .gridRow(request.gridRow())
                .gridColumn(request.gridColumn())
                .status(TableStatus.AVAILABLE)
                .build();
        RestaurantTable saved = tableRepository.save(table);
        return ApiResponse.ok(toDto(saved));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('TABLE_MANAGE')")
    @Transactional
    public ApiResponse<TableDto> update(@PathVariable UUID id, @RequestBody UpdateTableRequest request,
                                         @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        RestaurantTable table = tableRepository.findById(id).orElseThrow(() -> ApiException.notFound("Table not found"));
        // Bistrodesk Phase 2: same branch-isolation check as list/create above.
        branchAccessService.assertAccess(branchAccessService.resolve(principal), table.getFloor().getBranch().getId());
        if (table.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(RestaurantTable.class, id);
        }

        if (request.name() != null) table.setName(request.name());
        if (request.seatingCapacity() != null) table.setSeatingCapacity(request.seatingCapacity());
        if (request.section() != null) table.setSection(request.section());
        if (request.gridRow() != null) table.setGridRow(request.gridRow());
        if (request.gridColumn() != null) table.setGridColumn(request.gridColumn());
        if (request.active() != null) table.setActive(request.active());

        boolean statusChanged = false;
        if (request.status() != null) {
            TableStatus newStatus = parseStatus(request.status());
            if (!table.canTransitionTo(newStatus)) {
                throw ApiException.conflict("INVALID_TABLE_TRANSITION",
                        "Table " + table.getName() + " cannot move from " + table.getStatus() + " to " + newStatus);
            }
            statusChanged = table.getStatus() != newStatus;
            table.setStatus(newStatus);
        }

        RestaurantTable saved = tableRepository.save(table);

        if (statusChanged) {
            eventPublisher.publish("/topic/tables", "TABLE_STATUS_CHANGED", saved.getId(), saved.getVersion(),
                    Map.of("tableName", saved.getName(), "status", saved.getStatus().name()));
        }

        return ApiResponse.ok(toDto(saved));
    }

    /** Bistrodesk follow-up requirement #5 (table creation/seeding fix): the "which floor should
     * this new table go on" picker {@code TablesPage.tsx}'s Add Table screen needs - deliberately a
     * dedicated read-only lookup rather than deriving a floor id from an already-existing table
     * (the previous {@code tables[0]?.floorId} approach), which broke the moment a branch had zero
     * *currently active* tables - either a genuinely brand-new branch, or one where every table had
     * simply been deactivated - even though its Floor row (and, since {@link TableSeedingService},
     * its default tables) still exists. Same branch-resolution shape as {@link #list}, except there
     * is no "browse across every accessible branch" fallback here - adding a table always needs
     * exactly one target branch, so an unresolvable {@code BRANCH_REQUIRED} is left to propagate
     * rather than silently guessing. */
    @GetMapping("/floors")
    @PreAuthorize("hasAuthority('TABLE_VIEW') or hasAuthority('TABLE_MANAGE')")
    public ApiResponse<List<TableFloorDto>> listFloors(@RequestParam(required = false) UUID branchId,
                                                         @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        UUID resolvedBranchId;
        if (branchId != null) {
            branchAccessService.assertAccess(requester, branchId);
            resolvedBranchId = branchId;
        } else {
            resolvedBranchId = branchAccessService.resolveEffectiveBranchId(requester, null);
        }
        List<Floor> floors = floorRepository.findByBranch_IdOrderByDisplayOrderAsc(resolvedBranchId);
        return ApiResponse.ok(floors.stream().map(f -> new TableFloorDto(f.getId(), f.getName())).toList());
    }

    /** Bistrodesk follow-up requirement #5 ("every table... should have option to edit/update and
     * delete"): a soft-delete, exactly like {@code BranchController#update}'s deactivate action and
     * this same entity's existing {@code active} flag {@link #list} already filters on - a real
     * hard-delete risks orphaning (or FK-failing on) any historical {@code Order} that was ever
     * taken at this table, which a table this codebase has actually used could easily have. Blocked
     * while the table is anything other than {@code AVAILABLE} (an occupied/reserved/in-service
     * table) so a manager can't delete out from under a live order - release or complete it first,
     * same "explain why, don't silently orphan something" convention as {@code BranchController
     * #update}'s active-terminal check. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('TABLE_MANAGE')")
    @Transactional
    public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        RestaurantTable table = tableRepository.findById(id).orElseThrow(() -> ApiException.notFound("Table not found"));
        branchAccessService.assertAccess(branchAccessService.resolve(principal), table.getFloor().getBranch().getId());
        if (table.getStatus() != TableStatus.AVAILABLE) {
            throw ApiException.conflict("TABLE_IN_USE",
                    "Table " + table.getName() + " is currently " + table.getStatus()
                            + ". Release it back to Available before deleting.");
        }
        table.setActive(false);
        tableRepository.save(table);
        return ApiResponse.ok(null);
    }

    private TableStatus parseStatus(String raw) {
        try {
            return TableStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown table status: " + raw);
        }
    }

    private TableDto toDto(RestaurantTable t) {
        return new TableDto(t.getId(), t.getFloor().getId(), t.getName(), t.getSeatingCapacity(), t.getSection(),
                t.getStatus().name(), t.getGridRow(), t.getGridColumn(), t.isActive(), t.getVersion());
    }
}
