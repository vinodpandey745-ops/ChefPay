package com.chefpay.server.reports;

import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import com.chefpay.core.domain.Payment;
import com.chefpay.core.domain.Recipe;
import com.chefpay.core.repository.PaymentRepository;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.recipe.RecipeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F2.4 - "Menu Engineering Matrix," the classic Kasavana &amp; Smith four-quadrant technique: every
 * {@link MenuItem} actually sold in the date range is scored on popularity and profitability,
 * landing in one of four quadrants: {@code STAR} (high popularity, high margin - protect and
 * promote), {@code PLOWHORSE} (high popularity, low margin - a volume driver that's barely
 * profitable, consider a price or portion adjustment), {@code PUZZLE} (low popularity, high margin
 * - profitable but underordered, consider promoting it), {@code DOG} (low popularity, low margin -
 * a genuine candidate for removal).
 *
 * <p><b>Popularity</b> matches the SRS's exact wording ("unit sales as a share of category sales"):
 * for each {@link com.chefpay.core.domain.MenuCategory}, an item's share is its quantity sold
 * divided by its category's total classifiable quantity sold; "high popularity" means that share is
 * at or above the category's own fair/equal share (1 / number of classifiable items in that
 * category) - equivalently, at or above that category's own average quantity sold per item. This
 * is deliberately category-relative, not restaurant-wide: a starter selling 40 units/month can be a
 * STAR among starters while an entree selling 40 units/month is a DOG among entrees, which a single
 * restaurant-wide average would have missed entirely.
 *
 * <p><b>Profitability</b> is contribution margin in absolute currency (price minus {@link
 * RecipeService#costPerServing}), compared against the whole date range's own average margin
 * across every classifiable item - the SRS does not tie profitability to category, only popularity,
 * so this stays restaurant-wide.
 *
 * <p>Only items with a full, costed {@link Recipe} can be classified - an item with no recipe (or
 * a recipe with any ingredient missing {@code InventoryItem#getCostPerUnit()}) has no margin to
 * compare, and is reported separately as "unclassified" rather than silently guessed at with a
 * zero/fake cost, which would corrupt the very averages every other item is judged against. An
 * item with no {@code MenuCategory} assigned is grouped into its own "Uncategorized" bucket for the
 * popularity comparison, rather than silently dropped.
 */
@Service
@RequiredArgsConstructor
public class MenuEngineeringService {

    private final PaymentRepository paymentRepository;
    private final RecipeService recipeService;

    @Transactional(readOnly = true)
    public MenuEngineeringDtos.MatrixResponseDto getMatrix(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.badRequest("MISSING_DATE_RANGE", "Both 'from' and 'to' dates are required.");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "'to' cannot be before 'from'.");
        }

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<Payment> payments = paymentRepository.findByVoidedFalseAndReceivedAtBetween(start, end);

        // Same "distinct orders behind these payments" dedupe ReportService#getSalesReport uses,
        // so a split-bill order's items are never double-counted.
        Map<UUID, Order> ordersById = new LinkedHashMap<>();
        for (Payment payment : payments) {
            ordersById.put(payment.getOrder().getId(), payment.getOrder());
        }

        record RawAgg(MenuItem menuItem, BigDecimal quantity, BigDecimal revenue) {
            RawAgg add(BigDecimal qty, BigDecimal rev) {
                return new RawAgg(menuItem, quantity.add(qty), revenue.add(rev));
            }
        }
        Map<UUID, RawAgg> raw = new LinkedHashMap<>();
        for (Order order : ordersById.values()) {
            for (OrderItem item : order.getItems()) {
                if (item.getStatus() == OrderItemStatus.CANCELLED || item.getStatus() == OrderItemStatus.VOIDED) {
                    continue;
                }
                MenuItem menuItem = item.getMenuItem();
                raw.merge(menuItem.getId(), new RawAgg(menuItem, item.getQuantity(), item.lineTotal()),
                        (a, b) -> a.add(b.quantity(), b.revenue()));
            }
        }

        List<MenuEngineeringDtos.MenuItemPerformanceDto> classifiable = new java.util.ArrayList<>();
        List<MenuEngineeringDtos.MenuItemPerformanceDto> unclassifiable = new java.util.ArrayList<>();
        for (RawAgg agg : raw.values()) {
            java.util.Optional<Recipe> recipeOpt = recipeService.findByMenuItem(agg.menuItem().getId());
            BigDecimal cost = recipeOpt.isEmpty() || !recipeOpt.get().isActive() ? null : recipeService.costPerServing(recipeOpt.get());
            BigDecimal margin = cost == null ? null : agg.menuItem().getPrice().subtract(cost);
            String categoryName = agg.menuItem().getCategory() == null ? "Uncategorized" : agg.menuItem().getCategory().getName();
            MenuEngineeringDtos.MenuItemPerformanceDto dto = new MenuEngineeringDtos.MenuItemPerformanceDto(
                    agg.menuItem().getId(), agg.menuItem().getName(), categoryName, agg.quantity(), agg.revenue(),
                    agg.menuItem().getPrice(), cost, margin, cost == null ? "NO_RECIPE_DATA" : null);
            (cost == null ? unclassifiable : classifiable).add(dto);
        }

        // Overall (cross-category) average quantity - report-header context only, not used for
        // classification (see class javadoc).
        BigDecimal avgQuantity = BigDecimal.ZERO;
        BigDecimal avgMargin = BigDecimal.ZERO;
        if (!classifiable.isEmpty()) {
            BigDecimal totalQty = classifiable.stream().map(MenuEngineeringDtos.MenuItemPerformanceDto::quantitySold)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalMargin = classifiable.stream().map(MenuEngineeringDtos.MenuItemPerformanceDto::contributionMargin)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            avgQuantity = totalQty.divide(BigDecimal.valueOf(classifiable.size()), 3, RoundingMode.HALF_UP);
            avgMargin = totalMargin.divide(BigDecimal.valueOf(classifiable.size()), 2, RoundingMode.HALF_UP);
        }

        // Popularity (F2.4: "unit sales as a share of category sales") - each category's own
        // average quantity sold per classifiable item is that category's "fair share" cutoff.
        Map<String, BigDecimal> categoryAvgQuantity = new java.util.HashMap<>();
        Map<String, List<MenuEngineeringDtos.MenuItemPerformanceDto>> byCategory = new java.util.LinkedHashMap<>();
        for (MenuEngineeringDtos.MenuItemPerformanceDto d : classifiable) {
            byCategory.computeIfAbsent(d.categoryName(), k -> new java.util.ArrayList<>()).add(d);
        }
        for (Map.Entry<String, List<MenuEngineeringDtos.MenuItemPerformanceDto>> entry : byCategory.entrySet()) {
            BigDecimal categoryTotalQty = entry.getValue().stream().map(MenuEngineeringDtos.MenuItemPerformanceDto::quantitySold)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            categoryAvgQuantity.put(entry.getKey(),
                    categoryTotalQty.divide(BigDecimal.valueOf(entry.getValue().size()), 3, RoundingMode.HALF_UP));
        }

        BigDecimal finalAvgMargin = avgMargin;
        List<MenuEngineeringDtos.MenuItemPerformanceDto> classified = classifiable.stream()
                .map(d -> {
                    BigDecimal categoryAvg = categoryAvgQuantity.getOrDefault(d.categoryName(), BigDecimal.ZERO);
                    boolean highPopularity = d.quantitySold().compareTo(categoryAvg) >= 0;
                    boolean highMargin = d.contributionMargin().compareTo(finalAvgMargin) >= 0;
                    String classification = highPopularity
                            ? (highMargin ? "STAR" : "PLOWHORSE")
                            : (highMargin ? "PUZZLE" : "DOG");
                    return new MenuEngineeringDtos.MenuItemPerformanceDto(d.menuItemId(), d.menuItemName(), d.categoryName(),
                            d.quantitySold(), d.revenue(), d.price(), d.recipeCost(), d.contributionMargin(), classification);
                })
                .sorted((a, b) -> b.revenue().compareTo(a.revenue()))
                .toList();

        List<MenuEngineeringDtos.MenuItemPerformanceDto> allItems = new java.util.ArrayList<>(classified);
        allItems.addAll(unclassifiable.stream().sorted((a, b) -> b.revenue().compareTo(a.revenue())).toList());

        return new MenuEngineeringDtos.MatrixResponseDto(from, to, avgQuantity, avgMargin,
                classifiable.size(), unclassifiable.size(), allItems);
    }
}
