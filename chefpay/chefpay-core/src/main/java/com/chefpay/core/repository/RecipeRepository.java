package com.chefpay.core.repository;

import com.chefpay.core.domain.Recipe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

    Optional<Recipe> findByMenuItemId(UUID menuItemId);
}
