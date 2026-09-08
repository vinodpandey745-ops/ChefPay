package com.chefpay.core.repository;

import com.chefpay.core.domain.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, UUID> {
    List<MenuCategory> findByActiveTrueOrderByDisplayOrderAsc();

    /** Round 10: lets the AI Menu Import feature find-or-create a category by name (e.g. from a
     * photo) without ever creating a case-only duplicate of one that already exists. */
    Optional<MenuCategory> findByNameIgnoreCase(String name);

    /** Round 18: guards category delete/merge - a category with subcategories under it can't be
     * hard-deleted or merged away until those are reassigned/removed first (see MenuController). */
    boolean existsByParentCategoryId(UUID parentCategoryId);
}
