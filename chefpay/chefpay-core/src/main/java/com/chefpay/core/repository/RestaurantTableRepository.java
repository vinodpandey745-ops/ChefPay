package com.chefpay.core.repository;

import com.chefpay.core.domain.RestaurantTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, UUID> {
    List<RestaurantTable> findByActiveTrueOrderByGridRowAscGridColumnAsc();

    List<RestaurantTable> findByFloorId(UUID floorId);

    /** Round 11: branch-scoped variant of {@link #findByActiveTrueOrderByGridRowAscGridColumnAsc}
     * for a restaurant CHAIN's table matrix - see {@code TableController#list}'s javadoc for when
     * each query is used. */
    List<RestaurantTable> findByFloor_Branch_IdAndActiveTrueOrderByGridRowAscGridColumnAsc(UUID branchId);

    /** Bistrodesk Phase 2: multi-branch variant for a caller restricted to more than one specific
     * branch (without the unrestricted {@code VIEW_ALL_BRANCHES} permission) - see {@code
     * BranchAccessService}. */
    List<RestaurantTable> findByFloor_Branch_IdInAndActiveTrueOrderByGridRowAscGridColumnAsc(Collection<UUID> branchIds);

    /** Bistrodesk Phase 8 (requirement #24): the tables a reservation capacity check sums against -
     * every active table in a branch whose free-text {@link RestaurantTable#getSection()} matches a
     * given {@code Area}'s name (case-insensitively, since {@code Area} is just the catalog that
     * populates this field, not a real FK - see {@code Area}'s own javadoc). */
    List<RestaurantTable> findByFloor_Branch_IdAndSectionIgnoreCaseAndActiveTrue(UUID branchId, String section);

    /** Bistrodesk follow-up requirement #5 (table creation/seeding fix): deliberately counts EVERY
     * table for this branch, active or not - {@code TableSeedingService#ensureDefaultTableSetup}'s
     * idempotency check needs "has this branch ever had a table" (so a manager who deliberately
     * deactivates every table down to zero never gets them silently re-seeded), not "does it
     * currently have a visible one." */
    long countByFloor_Branch_Id(UUID branchId);
}
