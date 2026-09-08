package com.chefpay.core.repository;

import com.chefpay.core.domain.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {
    List<MenuItem> findByActiveTrueOrderByNameAsc();

    List<MenuItem> findByCategoryIdAndActiveTrue(UUID categoryId);

    /** Round 18: every item in a category regardless of active/inactive - used by category
     * delete (must be genuinely empty, not just empty-of-active-items) and merge (every item,
     * including previously soft-deleted ones, follows its category into the merge target rather
     * than being silently orphaned/left behind under a category that's about to disappear). */
    List<MenuItem> findByCategoryId(UUID categoryId);

    long countByCategoryId(UUID categoryId);
}
