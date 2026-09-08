package com.chefpay.core.repository;

import com.chefpay.core.domain.RecipeLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecipeLineRepository extends JpaRepository<RecipeLine, UUID> {

    List<RecipeLine> findByRecipeIdOrderByCreatedAtAsc(UUID recipeId);
}
