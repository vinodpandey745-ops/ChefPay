package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.reports.MenuEngineeringDtos} - Round 14 (F2.4). */
public final class MenuEngineeringDtos {

    private MenuEngineeringDtos() {
    }

    public record MenuItemPerformanceDto(UUID menuItemId, String menuItemName, String categoryName, BigDecimal quantitySold,
                                          BigDecimal revenue, BigDecimal price, BigDecimal recipeCost,
                                          BigDecimal contributionMargin, String classification) {
    }

    public record MatrixResponseDto(LocalDate from, LocalDate to, BigDecimal averageQuantitySold,
                                     BigDecimal averageContributionMargin, int classifiedItemCount,
                                     int unclassifiedItemCount, List<MenuItemPerformanceDto> items) {
    }
}
