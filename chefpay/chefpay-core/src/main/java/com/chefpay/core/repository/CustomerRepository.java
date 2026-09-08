package com.chefpay.core.repository;

import com.chefpay.core.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** Unfiltered - retained only for the SQLite {@code DataSeeder} branch-backfill sweep, which by
     * definition must see every row including not-yet-backfilled ones. Every request-serving path
     * must go through one of the branch-scoped finders below instead - unlike {@code InventoryItem},
     * {@link Customer#getBranch()} is never a legitimate long-term "shared/unassigned" state (see
     * its javadoc), so there is no null-tolerant in-memory filtering fallback here the way
     * {@code InventoryService} has for inventory items. */
    List<Customer> findAllByOrderByNameAsc();

    /** Unfiltered phone/name search - retained only as {@code CustomerController}'s pre-backfill
     * defensive fallback for an unrestricted caller (see its {@code legacySearch}); every normal
     * request-serving search goes through {@link #searchByBranch}/{@link #searchByBranches}
     * instead. */
    List<Customer> findByPhoneContainingOrNameContainingIgnoreCaseOrderByNameAsc(String phone, String name);

    /** Bistrodesk branch-isolation release (requirement #6): a customer belongs to exactly one
     * branch - these are the branch-scoped equivalents of the old unfiltered finders, mirroring
     * {@code RestaurantTableRepository}'s {@code _IdAnd...}/{@code _IdIn...} pair. */
    List<Customer> findByBranch_IdOrderByNameAsc(UUID branchId);

    List<Customer> findByBranch_IdInOrderByNameAsc(Collection<UUID> branchIds);

    /** Branch-scoped equivalents of the old {@code findByPhoneContainingOrNameContainingIgnoreCase
     * OrderByNameAsc} - a derived-query name can't express "branch AND (phone OR name)" so these use
     * JPQL directly. Backs the phone-lookup used by the Delivery/Pickup quick-order dialog and the
     * Customers screen's search box - matches on phone OR name, same query string passed for both. */
    @Query("select c from Customer c where c.branch.id = :branchId "
            + "and (c.phone like concat('%', :query, '%') or lower(c.name) like lower(concat('%', :query, '%'))) "
            + "order by c.name asc")
    List<Customer> searchByBranch(@Param("branchId") UUID branchId, @Param("query") String query);

    @Query("select c from Customer c where c.branch.id in :branchIds "
            + "and (c.phone like concat('%', :query, '%') or lower(c.name) like lower(concat('%', :query, '%'))) "
            + "order by c.name asc")
    List<Customer> searchByBranches(@Param("branchIds") Collection<UUID> branchIds, @Param("query") String query);
}
