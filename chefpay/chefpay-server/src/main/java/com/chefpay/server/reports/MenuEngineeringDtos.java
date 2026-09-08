package com.chefpay.server.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MenuEngineeringDtos {

    private MenuEngineeringDtos() {
    }

    public record MenuItemPerformanceDto(UUID menuItemId, String menuItemName, String categoryName, BigDecimal quantitySold,
                                          BigDecimal revenue, BigDecimal price, BigDecimal recipeCost,
                                          BigDecimal contributionMargin, String classification) {
    }

    /** {@code averageQuantitySold} is now the overall (cross-category) average, kept for a
     * restaurant-wide sense of scale on the report header - the actual STAR/PLOWHORSE/PUZZLE/DOG
     * classification instead compares each item's quantity against its OWN category's average
     * (F2.4: "popularity as a share of category sales"), computed per category internally. */
    public record MatrixResponseDto(LocalDate from, LocalDate to, BigDecimal averageQuantitySold,
                                     BigDecimal averageContributionMargin, int classifiedItemCount,
                                     int unclassifiedItemCount, List<MenuItemPerformanceDto> items) {
    }
}
