package com.chefpay.core.repository;

import com.chefpay.core.domain.Floor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FloorRepository extends JpaRepository<Floor, UUID> {
    List<Floor> findAllByOrderByDisplayOrderAsc();

    /** Bistrodesk follow-up requirement #5 (table creation/seeding fix): the branch-scoped floor
     * list a "which floor should this new table go on" picker needs - see {@code TableController
     * #listFloors} and {@code TableSeedingService#ensureDefaultTableSetup}, both of which need to
     * find (or confirm the absence of) a specific branch's floors without loading every floor in
     * the install first. */
    List<Floor> findByBranch_IdOrderByDisplayOrderAsc(UUID branchId);
}
