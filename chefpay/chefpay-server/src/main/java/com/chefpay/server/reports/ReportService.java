package com.chefpay.server.reports;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.MenuCategory;
import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import com.chefpay.core.domain.OrderType;
import com.chefpay.core.domain.Payment;
import com.chefpay.core.domain.PaymentMethod;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.PaymentRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5's Reports slice (ARCHITECTURE.md §15) - deliberately scoped to one report that answers
 * the question every shift actually asks first ("how did we do, and what sold"), built entirely
 * off data this app already records (no new tracking needed): {@code Payment} for money in the
 * door, {@code Order}/{@code OrderItem} for what was sold. Same "real but focused" scope decision
 * this codebase already made for Phase 5a's Inventory/Dashboard/Audit slice - a fuller reporting
 * suite (staff performance, prep-time/delay analytics off {@code OrderItem}'s timestamps, CSV/PDF
 * export, saved report schedules) is a natural next phase, not something to fake here.
 *
 * <p>Sales are anchored on {@link Payment} rather than {@code Order.createdAt} - a report for
 * "yesterday" should show money that actually landed yesterday, not orders merely opened then
 * (an order started late one night and paid after midnight would otherwise show up on the wrong
 * day's totals). This mirrors {@code DashboardService.getSummary}'s "today's sales" KPI, just
 * over a caller-chosen range instead of a fixed today-only window.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final PaymentRepository paymentRepository;
    private final RestaurantRepository restaurantRepository;
    private final BranchRepository branchRepository;

    /**
     * Bug #11 (Bistrodesk consolidated requirements): "today"/a date range must mean the
     * RESTAURANT's business day, not whatever timezone the server process's JVM happens to be
     * running in. Every {@code LocalDateTime} this app stores (e.g. {@link Payment#getReceivedAt()},
     * always written via {@code LocalDateTime.now()}) is a naive timestamp in the server's own
     * system-default zone - it carries no zone info of its own. The bug: {@code getSalesReport}/
     * {@code getConsolidatedBranchReport} used to build their query boundaries via
     * {@code from.atStartOfDay()} - a NAIVE midnight, silently assumed to already be "midnight in
     * whatever zone the data was written in" - with no actual zone conversion at all. The moment the
     * server's system zone differs from {@link Restaurant#getDefaultTimezone()} (e.g. a
     * cloud-hosted server in UTC for a restaurant configured for {@code Asia/Kolkata}, a whole
     * {@code +05:30} offset), "today" reported here silently drifted from the actual local business
     * day - payments in the first/last few hours of the local day were counted on the wrong
     * calendar date, or missed/double-counted at the boundary.
     *
     * <p>Fix: interpret {@code from}/{@code to} as calendar dates in the restaurant's configured
     * {@code defaultTimezone}, convert that boundary to a precise {@link Instant}, then convert
     * that instant back into the server's own {@link ZoneId#systemDefault()} - the zone every stored
     * {@code LocalDateTime} was actually written in - to get the correct query boundary. When the
     * server's system zone already matches the restaurant's configured zone (the common case for an
     * on-prem/LAN deployment set up correctly), this produces the exact same boundary as before -
     * this fix only changes behavior when the two zones genuinely differ, which is precisely the
     * bug. Once branches can have independent configuration (a later phase), this should resolve
     * timezone per-branch rather than per-restaurant; for now there is exactly one {@link
     * Restaurant} row, so this is unambiguous.
     *
     * <p>Follow-up requirement ("Reports - Date Column": "the correct branch/local time zone is
     * used") resolved the "later phase" noted above: {@code branchIds} is the same access-checked
     * filter every report method here already takes - when it resolves to exactly ONE branch (a
     * branch-restricted caller, or an unrestricted caller who explicitly picked a single branch),
     * {@link #businessZone(Set)} below prefers THAT branch's own {@code timezone} over the
     * install-wide {@code Restaurant#defaultTimezone}. A null/multi-branch filter (a caller
     * genuinely viewing more than one branch's data at once) has no single unambiguous "business
     * day" to anchor on, so it keeps using the restaurant-wide default exactly as before - this
     * change only ever narrows the zone for the single-branch case, never changes behavior for a
     * multi-branch/unrestricted view.
     */
    private LocalDateTime[] businessDateTimeRange(LocalDate from, LocalDate to, Set<UUID> branchIds) {
        ZoneId businessZone = businessZone(branchIds);
        Instant startInstant = from.atStartOfDay(businessZone).toInstant();
        Instant endInstant = to.plusDays(1).atStartOfDay(businessZone).toInstant();
        ZoneId serverZone = ZoneId.systemDefault();
        return new LocalDateTime[] {
                LocalDateTime.ofInstant(startInstant, serverZone),
                LocalDateTime.ofInstant(endInstant, serverZone)
        };
    }

    /** Restaurant-wide zone only - used wherever no single branch is in scope (see {@link
     * #businessZone(Set)} for the branch-aware overload used by every report method above). */
    private ZoneId businessZone() {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst().orElse(null);
        return zoneOrSystemDefault(restaurant == null ? null : restaurant.getDefaultTimezone());
    }

    /** Follow-up requirement ("Reports - Date Column"): prefers the single resolved branch's own
     * {@code timezone} when {@code branchIds} narrows to exactly one; otherwise falls back to
     * {@link #businessZone()} unchanged. See {@link #businessDateTimeRange}'s javadoc for the full
     * reasoning on why only the single-branch case is narrowed. */
    private ZoneId businessZone(Set<UUID> branchIds) {
        if (branchIds != null && branchIds.size() == 1) {
            Branch branch = branchRepository.findById(branchIds.iterator().next()).orElse(null);
            String branchTz = branch == null ? null : branch.getTimezone();
            if (branchTz != null && !branchTz.isBlank()) {
                return zoneOrSystemDefault(branchTz);
            }
        }
        return businessZone();
    }

    private ZoneId zoneOrSystemDefault(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception malformed) {
            // A malformed/legacy timezone string must never break every report on the whole
            // install - fall back to the server's own zone (the pre-fix behavior) rather than
            // throwing out of a read-only report endpoint.
            return ZoneId.systemDefault();
        }
    }

    /** Bug #11: "today" (the default date range when a caller omits {@code from}/{@code to} -
     * see {@code ReportController}) must also mean the restaurant's business day, not the server's
     * system date - see {@link #businessDateTimeRange}'s javadoc for the full reasoning. Kept
     * zone-agnostic of any single branch (the controller resolves "today" before it knows the
     * access-checked branch filter) - see {@link #today(UUID)} for the branch-aware overload used
     * once a specific {@code branchId} query param is present. */
    public LocalDate today() {
        return LocalDate.now(businessZone());
    }

    /** Follow-up requirement ("Reports - Date Column"): when the caller passed an explicit {@code
     * branchId}, "today" should mean THAT branch's own business day, not just the install-wide
     * default - see {@code ReportController#sales}/{@code #branches}, which now call this instead
     * of {@link #today()} whenever {@code branchId} is present on the request. Falls back to {@link
     * #today()} for a null/unknown/blank-timezone branch, so an omitted or invalid branchId never
     * changes today's existing behavior. */
    public LocalDate today(UUID branchId) {
        if (branchId == null) {
            return today();
        }
        Branch branch = branchRepository.findById(branchId).orElse(null);
        String branchTz = branch == null ? null : branch.getTimezone();
        if (branchTz == null || branchTz.isBlank()) {
            return today();
        }
        return LocalDate.now(zoneOrSystemDefault(branchTz));
    }

    /** {@code branchIds == null} means unrestricted (every branch, the pre-Phase-5 behavior);
     * otherwise a payment whose order isn't attributed to one of these branches is excluded - see
     * {@link Order#getEffectiveBranch()}'s javadoc for what "attributed" means. A branch-less
     * payment (only possible for an order created before Phase 2's {@code Order.branch} column
     * existed) is excluded from a FILTERED report the same way {@code
     * com.chefpay.server.dashboard.DashboardService} excludes one, for the same reason: it can't
     * honestly be counted as any one specific branch's sales. */
    private boolean inScope(Payment payment, Set<UUID> branchIds) {
        if (branchIds == null) {
            return true;
        }
        Branch branch = payment.getOrder().getEffectiveBranch();
        return branch != null && branchIds.contains(branch.getId());
    }

    @Transactional(readOnly = true)
    public ReportDtos.SalesReportDto getSalesReport(LocalDate from, LocalDate to, Set<UUID> branchIds) {
        if (from == null || to == null) {
            throw ApiException.badRequest("MISSING_DATE_RANGE", "Both 'from' and 'to' dates are required.");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "'to' cannot be before 'from'.");
        }

        LocalDateTime[] range = businessDateTimeRange(from, to, branchIds);
        LocalDateTime start = range[0];
        LocalDateTime end = range[1];
        List<Payment> payments = paymentRepository.findByVoidedFalseAndReceivedAtBetween(start, end).stream()
                .filter(p -> inScope(p, branchIds))
                .toList();

        BigDecimal totalSales = payments.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<PaymentMethod, List<Payment>> byMethod = new EnumMap<>(PaymentMethod.class);
        for (Payment payment : payments) {
            byMethod.computeIfAbsent(payment.getMethod(), k -> new ArrayList<>()).add(payment);
        }
        List<ReportDtos.PaymentMethodTotalDto> paymentMethodBreakdown = byMethod.entrySet().stream()
                .map(e -> new ReportDtos.PaymentMethodTotalDto(e.getKey().name(),
                        e.getValue().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                        e.getValue().size()))
                .sorted(Comparator.comparing(ReportDtos.PaymentMethodTotalDto::total).reversed())
                .toList();

        // Distinct orders behind these payments (a split bill has several payments against one
        // order) - keyed by id rather than relying on entity equals/hashCode, which isn't a safe
        // way to dedupe JPA entities across a collection.
        Map<UUID, Order> ordersById = new LinkedHashMap<>();
        for (Payment payment : payments) {
            ordersById.put(payment.getOrder().getId(), payment.getOrder());
        }

        Map<UUID, ItemAggregate> itemTotals = new LinkedHashMap<>();
        // Keyed by category id (nullable categories collapse into one "Uncategorized" bucket via
        // a sentinel id) rather than name, since two categories could theoretically share a name.
        Map<UUID, ItemAggregate> categoryTotals = new LinkedHashMap<>();
        for (Order order : ordersById.values()) {
            for (OrderItem item : order.getItems()) {
                if (item.getStatus() == OrderItemStatus.CANCELLED || item.getStatus() == OrderItemStatus.VOIDED) {
                    continue;
                }
                MenuItem menuItem = item.getMenuItem();
                itemTotals.computeIfAbsent(menuItem.getId(), k -> new ItemAggregate(menuItem.getName()))
                        .add(item.getQuantity(), item.lineTotal());

                MenuCategory category = menuItem.getCategory();
                UUID categoryKey = category == null ? UNCATEGORIZED_KEY : category.getId();
                String categoryName = category == null ? "Uncategorized" : category.getName();
                categoryTotals.computeIfAbsent(categoryKey, k -> new ItemAggregate(categoryName))
                        .add(item.getQuantity(), item.lineTotal());
            }
        }
        List<ReportDtos.TopItemDto> topItems = itemTotals.values().stream()
                .sorted(Comparator.comparing((ItemAggregate a) -> a.revenue).reversed())
                .limit(15)
                .map(a -> new ReportDtos.TopItemDto(a.name, a.quantity, a.revenue))
                .toList();
        List<ReportDtos.CategoryTotalDto> categoryBreakdown = categoryTotals.values().stream()
                .sorted(Comparator.comparing((ItemAggregate a) -> a.revenue).reversed())
                .map(a -> new ReportDtos.CategoryTotalDto(a.name, a.quantity, a.revenue))
                .toList();

        // Order-type breakdown - one row per distinct order behind these payments, so a split
        // bill's several payments don't double-count its order.
        Map<OrderType, OrderTypeAggregate> orderTypeTotals = new LinkedHashMap<>();
        for (Order order : ordersById.values()) {
            orderTypeTotals.computeIfAbsent(order.getOrderType(), k -> new OrderTypeAggregate())
                    .add(order.getTotalAmount());
        }
        List<ReportDtos.OrderTypeTotalDto> orderTypeBreakdown = orderTypeTotals.entrySet().stream()
                .map(e -> new ReportDtos.OrderTypeTotalDto(e.getKey().name(), e.getValue().count, e.getValue().revenue))
                .sorted(Comparator.comparing(ReportDtos.OrderTypeTotalDto::revenue).reversed())
                .toList();

        // Employee breakdown - attributed to who processed each payment (Payment.receivedBy),
        // NOT who served the order; see ReportDtos.EmployeeTotalDto's javadoc for why that's the
        // only attribution that stays unambiguous for split bills.
        Map<UUID, EmployeeAggregate> employeeTotals = new LinkedHashMap<>();
        for (Payment payment : payments) {
            AppUser receivedBy = payment.getReceivedBy();
            UUID key = receivedBy == null ? UNCATEGORIZED_KEY : receivedBy.getId();
            String name = receivedBy == null ? "Unknown" : receivedBy.getDisplayName();
            employeeTotals.computeIfAbsent(key, k -> new EmployeeAggregate(name)).add(payment.getAmount());
        }
        List<ReportDtos.EmployeeTotalDto> employeeBreakdown = employeeTotals.values().stream()
                .sorted(Comparator.comparing((EmployeeAggregate a) -> a.total).reversed())
                .map(a -> new ReportDtos.EmployeeTotalDto(a.name, a.total, a.count))
                .toList();

        // Tip breakdown - attributed to Order.waiter (who served the table), a deliberately
        // different attribution than the employee breakdown above; see ReportDtos.TipTotalDto's
        // javadoc. Only orders that actually carried a tip contribute.
        Map<UUID, EmployeeAggregate> tipTotals = new LinkedHashMap<>();
        BigDecimal totalTips = BigDecimal.ZERO;
        for (Order order : ordersById.values()) {
            BigDecimal tip = order.getTipAmount();
            if (tip == null || tip.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            totalTips = totalTips.add(tip);
            AppUser waiter = order.getWaiter();
            UUID key = waiter == null ? UNCATEGORIZED_KEY : waiter.getId();
            String name = waiter == null ? "Unassigned" : waiter.getDisplayName();
            tipTotals.computeIfAbsent(key, k -> new EmployeeAggregate(name)).add(tip);
        }
        List<ReportDtos.TipTotalDto> tipBreakdown = tipTotals.values().stream()
                .sorted(Comparator.comparing((EmployeeAggregate a) -> a.total).reversed())
                .map(a -> new ReportDtos.TipTotalDto(a.name, a.total, a.count))
                .toList();

        long orderCount = ordersById.size();
        BigDecimal averageOrderValue = orderCount == 0
                ? BigDecimal.ZERO
                : totalSales.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP);

        return new ReportDtos.SalesReportDto(from, to, totalSales, orderCount, payments.size(),
                averageOrderValue, paymentMethodBreakdown, topItems, categoryBreakdown, orderTypeBreakdown,
                employeeBreakdown, tipBreakdown, totalTips);
    }

    /**
     * Round 14 (F2.5) - see {@link ReportDtos.ConsolidatedBranchReportDto}'s javadoc for the
     * "unassigned bucket, never double-counted" design this method implements. Deliberately
     * attributes each PAYMENT (not each order) to a branch, matching {@link #getSalesReport}'s own
     * "totalSales is the sum of payment amounts" basis, so this report's grand total always equals
     * {@link #getSalesReport}'s for the same date range.
     *
     * <p>Bistrodesk Phase 5: {@code branchIds} restricts this consolidated view to a caller's own
     * accessible branches (or one drilled-into branch) the same way {@link #getSalesReport} does -
     * an unrestricted caller (null) still sees every branch, unchanged from before this phase.
     */
    @Transactional(readOnly = true)
    public ReportDtos.ConsolidatedBranchReportDto getConsolidatedBranchReport(LocalDate from, LocalDate to, Set<UUID> branchIds) {
        if (from == null || to == null) {
            throw ApiException.badRequest("MISSING_DATE_RANGE", "Both 'from' and 'to' dates are required.");
        }
        if (to.isBefore(from)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "'to' cannot be before 'from'.");
        }
        LocalDateTime[] range = businessDateTimeRange(from, to, branchIds);
        LocalDateTime start = range[0];
        LocalDateTime end = range[1];
        List<Payment> payments = paymentRepository.findByVoidedFalseAndReceivedAtBetween(start, end).stream()
                .filter(p -> inScope(p, branchIds))
                .toList();

        record BranchAgg(String name, BigDecimal total, Set<UUID> orderIds) {
        }
        Map<UUID, BranchAgg> byBranch = new LinkedHashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Payment payment : payments) {
            Order order = payment.getOrder();
            Branch branch = order.getEffectiveBranch();
            UUID key = branch == null ? UNASSIGNED_BRANCH_KEY : branch.getId();
            String name = branch == null ? "Unassigned (non-table orders)" : branch.getName();
            BranchAgg agg = byBranch.computeIfAbsent(key, k -> new BranchAgg(name, BigDecimal.ZERO, new java.util.HashSet<>()));
            byBranch.put(key, new BranchAgg(agg.name(), agg.total().add(payment.getAmount()), agg.orderIds()));
            byBranch.get(key).orderIds().add(order.getId());
            grandTotal = grandTotal.add(payment.getAmount());
        }

        List<ReportDtos.BranchTotalDto> branches = byBranch.entrySet().stream()
                .map(e -> new ReportDtos.BranchTotalDto(e.getKey().equals(UNASSIGNED_BRANCH_KEY) ? null : e.getKey(),
                        e.getValue().name(), e.getValue().total(), e.getValue().orderIds().size()))
                .sorted(Comparator.comparing(ReportDtos.BranchTotalDto::totalSales).reversed())
                .toList();

        return new ReportDtos.ConsolidatedBranchReportDto(from, to, grandTotal, branches);
    }

    /** Sentinel id for "no category" / "no receiving user" buckets - distinct from any real
     * entity id since {@link UUID#randomUUID()} is generated fresh per JVM run and never persisted. */
    private static final UUID UNCATEGORIZED_KEY = UUID.randomUUID();

    /** Sentinel id for the "non-table orders" bucket in {@link #getConsolidatedBranchReport} -
     * see {@link ReportDtos.ConsolidatedBranchReportDto}'s javadoc. */
    private static final UUID UNASSIGNED_BRANCH_KEY = UUID.randomUUID();

    private static final class ItemAggregate {
        private final String name;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal revenue = BigDecimal.ZERO;

        private ItemAggregate(String name) {
            this.name = name;
        }

        private void add(BigDecimal qty, BigDecimal lineTotal) {
            this.quantity = this.quantity.add(qty);
            this.revenue = this.revenue.add(lineTotal);
        }
    }

    private static final class OrderTypeAggregate {
        private long count = 0;
        private BigDecimal revenue = BigDecimal.ZERO;

        private void add(BigDecimal orderTotal) {
            this.count++;
            this.revenue = this.revenue.add(orderTotal == null ? BigDecimal.ZERO : orderTotal);
        }
    }

    private static final class EmployeeAggregate {
        private final String name;
        private long count = 0;
        private BigDecimal total = BigDecimal.ZERO;

        private EmployeeAggregate(String name) {
            this.name = name;
        }

        private void add(BigDecimal amount) {
            this.count++;
            this.total = this.total.add(amount == null ? BigDecimal.ZERO : amount);
        }
    }
}
