package com.chefpay.core.repository;

import com.chefpay.core.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    /** Unfiltered - retained only for the SQLite {@code DataSeeder} branch-backfill sweep and the
     * global {@code existsByNameIgnoreCase} duplicate-name check below. Every request-serving list
     * must go through one of the branch-scoped finders instead (see {@link Supplier#getBranch()}'s
     * javadoc - unlike {@code InventoryItem}, a supplier is never a legitimate long-term
     * shared/unassigned row here). */
    List<Supplier> findByActiveTrueOrderByNameAsc();

    /** Bistrodesk branch-isolation release (requirement #6): branch-scoped equivalents, mirroring
     * {@code RestaurantTableRepository}'s {@code _IdAnd...}/{@code _IdIn...} pair. */
    List<Supplier> findByBranch_IdAndActiveTrueOrderByNameAsc(UUID branchId);

    List<Supplier> findByBranch_IdInAndActiveTrueOrderByNameAsc(Collection<UUID> branchIds);

    boolean existsByNameIgnoreCase(String name);

    /** Bistrodesk branch-isolation release: duplicate-name uniqueness is now checked per-branch, not
     * globally - a name reused across two independent branches is not a duplicate. */
    boolean existsByNameIgnoreCaseAndBranch_Id(String name, UUID branchId);
}
