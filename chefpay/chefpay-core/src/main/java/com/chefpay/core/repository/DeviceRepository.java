package com.chefpay.core.repository;

import com.chefpay.core.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    Optional<Device> findByNameIgnoreCase(String name);

    /** Round 17: a returning terminal re-authenticates by its persisted terminalCode (stored in the
     * browser) rather than by name, so renaming a terminal's display name doesn't fork a second
     * Device row for what is physically the same station. */
    Optional<Device> findByTerminalCodeIgnoreCase(String terminalCode);

    List<Device> findAllByOrderByNameAsc();

    /** Phase 2: the client's terminal-select screen (item 12) - only active terminals, ordered by
     * their human-facing sequence rather than name. */
    List<Device> findByBranchIdAndActiveTrueOrderBySequenceNoAsc(UUID branchId);

    long countByBranchId(UUID branchId);

    /** Phase 2: bulk/single terminal creation needs the next free sequence number scoped to this
     * branch - a plain {@code MAX(sequence_no)} rather than a Spring Data derived method, since
     * "highest value in a column" isn't expressible as a method name. Null (no terminals yet in
     * this branch) is handled by the caller, same as any other first-row case in this codebase. */
    @Query("select max(d.sequenceNo) from Device d where d.branch.id = :branchId")
    Integer findMaxSequenceNoForBranch(@Param("branchId") UUID branchId);

    /** Bistrodesk follow-up requirement #10 (query optimization pass): {@code
     * BranchController#list} used to call {@link #countByBranchId} once per branch while building
     * its response - one extra round-trip per row, the classic N+1 shape - to show each branch's
     * terminal count. This single grouped query replaces that whole loop with one round-trip
     * regardless of how many branches exist; each row is {@code [UUID branchId, Long count]}. */
    @Query("select d.branch.id, count(d) from Device d group by d.branch.id")
    List<Object[]> countGroupedByBranch();
}
