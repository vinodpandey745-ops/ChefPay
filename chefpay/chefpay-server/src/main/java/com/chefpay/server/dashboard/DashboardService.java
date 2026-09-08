package com.chefpay.server.dashboard;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.InventoryItem;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import com.chefpay.core.domain.OrderStatus;
import com.chefpay.core.domain.Payment;
import com.chefpay.core.domain.RestaurantTable;
import com.chefpay.core.domain.TableStatus;
import com.chefpay.core.repository.InventoryItemRepository;
import com.chefpay.core.repository.OrderRepository;
import com.chefpay.core.repository.PaymentRepository;
import com.chefpay.core.repository.RestaurantTableRepository;
import com.chefpay.server.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A handful of at-a-glance KPIs pulled straight from existing repositories - Phase 5's Dashboard
 * slice (ARCHITECTURE.md §13). Deliberately not a historical reporting/analytics engine (date-range
 * comparisons, exports, prep-time metrics off {@code OrderItem}'s timestamps) - that's the larger
 * Reports scope still deferred; this is "what does the floor look like right now."
 *
 * <p>Bistrodesk Phase 5: every KPI here now accepts a {@code branchIds} filter (see
 * {@code com.chefpay.server.branch.BranchAccessService#resolveBranchFilter}) - {@code null} means
 * "no filter, every branch this caller may see" (an unrestricted caller, or a single-branch
 * install), a non-null {@link Set} restricts every underlying query to those branches. Before this
 * phase the whole class ran unfiltered for every caller regardless of role/branch assignment - a
 * Manager scoped to one branch could see the WHOLE restaurant's sales/orders/tables here, the exact
 * isolation gap Phase 1/2 closed for every other module. Order/table attribution goes through
 * {@link Order#getEffectiveBranch()} and {@code table.getFloor().getBranch()} respectively (a
 * {@link RestaurantTable} always belongs to exactly one branch via its floor - see that entity); a
 * branch-less order (only possible for one created before Phase 2's {@code Order.branch} column
 * existed) is excluded from a FILTERED view (it can't be attributed to any specific branch) but
 * still counted in the unfiltered "every branch" view, exactly as it always was.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Set<TableStatus> OCCUPIED_STATUSES = EnumSet.of(TableStatus.OCCUPIED, TableStatus.ORDER_PLACED,
            TableStatus.PREPARING, TableStatus.READY, TableStatus.BILL_REQUESTED, TableStatus.PAYMENT_PENDING);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RestaurantTableRepository tableRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final InventoryService inventoryService;

    /** Order/payment/table attribution: {@code branchIds == null} means unrestricted (include
     * everything); otherwise a branch-less item is excluded - it cannot be attributed to any of the
     * caller's specific branches, so silently including it in a restricted view would leak
     * unrelated-branch (or genuinely unattributed) revenue into that branch's own numbers. */
    private boolean inScope(Branch branch, Set<UUID> branchIds) {
        if (branchIds == null) {
            return true;
        }
        return branch != null && branchIds.contains(branch.getId());
    }

    /** {@link InventoryItem#getBranch()} uses the opposite null convention from an order/table -
     * see that field's own javadoc: null there means "shared/visible to every branch," not
     * "unattributed" - so a restricted view still includes it. */
    private boolean inventoryInScope(Branch branch, Set<UUID> branchIds) {
        return branchIds == null || branch == null || branchIds.contains(branch.getId());
    }

    @Transactional(readOnly = true)
    public DashboardDtos.SummaryDto getSummary(Set<UUID> branchIds) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1);

        List<Payment> todayPayments = paymentRepository.findByVoidedFalseAndReceivedAtBetween(start, end).stream()
                .filter(p -> inScope(p.getOrder().getEffectiveBranch(), branchIds))
                .toList();
        BigDecimal todaySalesTotal = todayPayments.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long todayOrderCount = orderRepository.findByCreatedAtBetween(start, end).stream()
                .filter(o -> inScope(o.getEffectiveBranch(), branchIds))
                .count();
        // PAID counts as inactive here too, same as OrderService#TABLE_INACTIVE_STATUSES and for
        // the same reason: nothing ever advances an order past PAID to CLOSED, so excluding only
        // CLOSED/CANCELLED left every fully-paid order inflating this count indefinitely - it also
        // disagreed with occupiedTableCount just below, which (via table status) already treats a
        // paid table as free.
        long openOrderCount = orderRepository.findByStatusNotInOrderByPriorityDescCreatedAtAsc(
                List.of(OrderStatus.PAID, OrderStatus.CLOSED, OrderStatus.CANCELLED)).stream()
                .filter(o -> inScope(o.getEffectiveBranch(), branchIds))
                .count();

        List<RestaurantTable> tables = tableRepository.findByActiveTrueOrderByGridRowAscGridColumnAsc().stream()
                .filter(t -> inScope(t.getFloor().getBranch(), branchIds))
                .toList();
        long occupiedTableCount = tables.stream().filter(t -> OCCUPIED_STATUSES.contains(t.getStatus())).count();

        long lowStockItemCount = inventoryItemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(i -> inventoryInScope(i.getBranch(), branchIds))
                .filter(inventoryService::isLowStock).count();

        return new DashboardDtos.SummaryDto(todaySalesTotal, todayOrderCount, todayPayments.size(), openOrderCount,
                occupiedTableCount, tables.size(), lowStockItemCount);
    }

    /** Round 12 §5 - backs the "Graphical"/"Both" dashboard mode's charts. Every list below is a
     * real, deterministic aggregation over existing entities (no synthetic/placeholder data) -
     * category/top-items/discount/tax/branch figures all use the same "created today" order window
     * {@link #getSummary}'s {@code todayOrderCount} already uses, for one consistent definition of
     * "today" across both dashboard modes.
     *
     * <p>Bistrodesk Phase 5: {@code branchIds} filters every list here exactly like {@link
     * #getSummary} (null = unrestricted). Branch attribution now goes through {@link
     * Order#getEffectiveBranch()} (the real, direct {@code Order.branch} column populated for every
     * order since Phase 2 - dine-in AND non-table alike) rather than the old ad hoc {@code
     * table.floor.branch} walk this method used before, which mis-labeled every delivery/pickup/
     * phone/online order as "Other (no table)" even when it genuinely had a recorded branch;
     * {@link #branchSales} below still buckets a genuinely branch-less order (only possible for one
     * created before that column existed) under one explicit "Other" bucket rather than dropping it. */
    @Transactional(readOnly = true)
    public DashboardDtos.AnalyticsDto getAnalytics(Set<UUID> branchIds) {
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);

        // ---- 7-day sales trend, by the day each non-voided payment actually landed ----
        LocalDateTime trendStart = today.minusDays(6).atStartOfDay();
        List<Payment> trendPayments = paymentRepository.findByVoidedFalseAndReceivedAtBetween(trendStart, todayEnd).stream()
                .filter(p -> inScope(p.getOrder().getEffectiveBranch(), branchIds))
                .toList();
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            byDay.put(today.minusDays(i), BigDecimal.ZERO);
        }
        for (Payment p : trendPayments) {
            byDay.merge(p.getReceivedAt().toLocalDate(), p.getAmount(), BigDecimal::add);
        }
        List<DashboardDtos.DailySalesPointDto> salesTrend = byDay.entrySet().stream()
                .map(e -> new DashboardDtos.DailySalesPointDto(e.getKey(), e.getValue()))
                .toList();

        // ---- today's orders/items, excluding cancelled/voided lines the same way BillingService does ----
        List<Order> todaysOrders = orderRepository.findByCreatedAtBetween(todayStart, todayEnd).stream()
                .filter(o -> inScope(o.getEffectiveBranch(), branchIds))
                .toList();
        List<OrderItem> billableItems = todaysOrders.stream()
                .flatMap(o -> o.getItems().stream())
                .filter(i -> i.getStatus() != OrderItemStatus.CANCELLED && i.getStatus() != OrderItemStatus.VOIDED)
                .toList();

        Map<String, BigDecimal> categoryTotals = new LinkedHashMap<>();
        for (OrderItem item : billableItems) {
            String categoryName = item.getMenuItem().getCategory() != null
                    ? item.getMenuItem().getCategory().getName() : "Uncategorized";
            categoryTotals.merge(categoryName, item.lineTotal(), BigDecimal::add);
        }
        List<DashboardDtos.CategorySalesDto> categoryBreakdown = categoryTotals.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> new DashboardDtos.CategorySalesDto(e.getKey(), e.getValue()))
                .toList();

        record ItemAgg(BigDecimal qty, BigDecimal revenue) {
            ItemAgg plus(BigDecimal moreQty, BigDecimal moreRevenue) {
                return new ItemAgg(qty.add(moreQty), revenue.add(moreRevenue));
            }
        }
        Map<String, ItemAgg> itemTotals = new LinkedHashMap<>();
        for (OrderItem item : billableItems) {
            itemTotals.merge(item.getMenuItem().getName(), new ItemAgg(item.getQuantity(), item.lineTotal()),
                    (a, b) -> a.plus(b.qty(), b.revenue()));
        }
        List<DashboardDtos.TopItemDto> topItems = itemTotals.entrySet().stream()
                .sorted((a, b) -> b.getValue().revenue().compareTo(a.getValue().revenue()))
                .limit(5)
                .map(e -> new DashboardDtos.TopItemDto(e.getKey(), e.getValue().qty(), e.getValue().revenue()))
                .toList();

        // ---- today's payments, by method and by branch ----
        List<Payment> todayPayments = paymentRepository.findByVoidedFalseAndReceivedAtBetween(todayStart, todayEnd).stream()
                .filter(p -> inScope(p.getOrder().getEffectiveBranch(), branchIds))
                .toList();
        Map<String, List<Payment>> byMethod = todayPayments.stream()
                .collect(Collectors.groupingBy(p -> p.getMethod().name(), LinkedHashMap::new, Collectors.toList()));
        List<DashboardDtos.PaymentMethodSalesDto> paymentMethods = byMethod.entrySet().stream()
                .map(e -> new DashboardDtos.PaymentMethodSalesDto(e.getKey(),
                        e.getValue().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().size()))
                .sorted((a, b) -> b.total().compareTo(a.total()))
                .toList();

        Map<String, BigDecimal> branchTotals = new LinkedHashMap<>();
        for (Payment p : todayPayments) {
            Branch branch = p.getOrder().getEffectiveBranch();
            String branchName = branch != null ? branch.getName() : "Other (unassigned)";
            branchTotals.merge(branchName, p.getAmount(), BigDecimal::add);
        }
        List<DashboardDtos.BranchSalesDto> branchSales = branchTotals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new DashboardDtos.BranchSalesDto(e.getKey(), e.getValue()))
                .toList();

        BigDecimal discountTotalToday = todaysOrders.stream().map(Order::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taxTotalToday = todaysOrders.stream().map(Order::getTaxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Order> billedToday = todaysOrders.stream().filter(o -> o.getBilledAt() != null).toList();
        BigDecimal averageOrderValueToday = billedToday.isEmpty()
                ? BigDecimal.ZERO
                : billedToday.stream().map(Order::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(billedToday.size()), 2, RoundingMode.HALF_UP);

        BigDecimal totalInventoryValue = inventoryItemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(i -> inventoryInScope(i.getBranch(), branchIds))
                .map(i -> i.getCostPerUnit() == null ? BigDecimal.ZERO : i.getQuantityOnHand().multiply(i.getCostPerUnit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DashboardDtos.AnalyticsDto(salesTrend, categoryBreakdown, topItems, paymentMethods, branchSales,
                discountTotalToday, taxTotalToday, averageOrderValueToday, totalInventoryValue);
    }
}
