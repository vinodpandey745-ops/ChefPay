package com.chefpay.server.tables;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Floor;
import com.chefpay.core.domain.RestaurantTable;
import com.chefpay.core.domain.TableStatus;
import com.chefpay.core.repository.FloorRepository;
import com.chefpay.core.repository.RestaurantTableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bistrodesk follow-up requirement #5 (the 10-item follow-up list's item 5): "default table setup
 * should assign to every branch while creation" - before this, a newly created branch got at most
 * an empty {@link Floor} (see {@code PlatformOwnerController#createBranch}'s own history, and
 * {@code DataSeeder#ensureDemoRestaurantData} which only ever seeded ONE branch, the very first
 * install-time one) and often not even that, leaving a branch owner with literally nothing to add
 * a table onto - {@code TablesPage.tsx}'s "Add Table" button derives its target floor from an
 * already-existing table ({@code tables[0]?.floorId}), so a branch with zero tables could never
 * create its first one either, compounding the problem. This is the single place that shape of
 * "every branch's table matrix starts non-empty" now lives, called from every path that can bring
 * a branch into existence or discover one that predates this fix: {@code
 * PlatformOwnerController#createBranch} (a brand new branch) and {@code DataSeeder}'s startup
 * backfill (every already-existing branch, exactly once each, self-healing any branch created
 * before this fix shipped).
 *
 * <p>Mirrors {@code DataSeeder#ensureDemoRestaurantData}'s original demo seed shape exactly - one
 * "Ground Floor" plus six tables (T01-T06, alternating 2/4-seat capacity, laid out 3-per-row) - so
 * a freshly created branch's table matrix looks exactly like day-one's own default install, not
 * some different placeholder shape.
 */
@Service
@RequiredArgsConstructor
public class TableSeedingService {

    private static final int DEFAULT_TABLE_COUNT = 6;

    private final FloorRepository floorRepository;
    private final RestaurantTableRepository tableRepository;

    /** Idempotent and safe to call on every branch on every startup (see {@code DataSeeder}'s own
     * backfill-method convention this mirrors): a branch that has EVER had a table - including one
     * a manager has since deactivated down to zero, see {@link RestaurantTableRepository
     * #countByFloor_Branch_Id}'s own javadoc - is left completely untouched. Only a branch with
     * zero tables, ever, gets the default floor+table set seeded. */
    public void ensureDefaultTableSetup(Branch branch) {
        if (tableRepository.countByFloor_Branch_Id(branch.getId()) > 0) {
            return;
        }
        List<Floor> floors = floorRepository.findByBranch_IdOrderByDisplayOrderAsc(branch.getId());
        Floor floor = floors.isEmpty()
                ? floorRepository.save(Floor.builder().branch(branch).name("Ground Floor").displayOrder(0).build())
                : floors.get(0);
        for (int i = 1; i <= DEFAULT_TABLE_COUNT; i++) {
            tableRepository.save(RestaurantTable.builder()
                    .floor(floor)
                    .name("T0" + i)
                    .seatingCapacity(i % 2 == 0 ? 4 : 2)
                    .status(TableStatus.AVAILABLE)
                    .gridRow((i - 1) / 3)
                    .gridColumn((i - 1) % 3)
                    .build());
        }
    }
}
