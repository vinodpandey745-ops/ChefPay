package com.chefpay.core.repository;

import com.chefpay.core.domain.PriceChangeSuggestion;
import com.chefpay.core.domain.PriceChangeSuggestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceChangeSuggestionRepository extends JpaRepository<PriceChangeSuggestion, UUID> {

    List<PriceChangeSuggestion> findByStatusOrderByDetectedAtDesc(PriceChangeSuggestionStatus status);

    List<PriceChangeSuggestion> findByOrderByDetectedAtDesc();

    /** Guards against re-raising a duplicate suggestion for the same item while an earlier one is
     * still pending review - see {@code PriceSuggestionService#detectAndRaise}'s javadoc. */
    Optional<PriceChangeSuggestion> findFirstByMenuItemIdAndStatusOrderByDetectedAtDesc(UUID menuItemId, PriceChangeSuggestionStatus status);
}
