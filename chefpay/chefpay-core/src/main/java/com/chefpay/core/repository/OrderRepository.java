package com.chefpay.core.repository;

import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderStatus;
import com.chefpay.core.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByStatusNotInOrderByPriorityDescCreatedAtAsc(List<OrderStatus> excludedStatuses);

    /** Round 11: branch-scoped variant of the query above, for a restaurant CHAIN's table matrix
     * (multiple branches in one deployment - see {@code ShellView}'s branch switcher).
     *
     * <p>Bistrodesk Phase 2: {@code Order} now has a direct {@code branch} column (see that field's
     * javadoc), so this prefers it - {@code o.branch.id = :branchId} - which finally, correctly
     * scopes NON-table orders too (delivery/pickup/online/phone), not just dine-in. The second half
     * of the OR is a fallback ONLY for a legacy row that predates that column ({@code o.branch IS
     * NULL}): a dine-in one narrows via {@code table.floor.branch} same as before Phase 2, and a
     * legacy non-table one (nothing to filter it by) is still included regardless, exactly
     * preserving old behavior for old data without pretending to fix what can't be retroactively
     * known. */
    @Query("SELECT o FROM Order o WHERE o.status NOT IN :excludedStatuses AND ("
            + "(o.branch IS NOT NULL AND o.branch.id = :branchId) "
            + "OR (o.branch IS NULL AND (o.table IS NULL OR o.table.floor.branch.id = :branchId))"
            + ") ORDER BY o.priority DESC, o.createdAt ASC")
    List<Order> findByStatusNotInAndBranch(@Param("excludedStatuses") List<OrderStatus> excludedStatuses, @Param("branchId") UUID branchId);

    /** Bistrodesk Phase 2: the multi-branch variant of {@link #findByStatusNotInAndBranch} for a
     * caller whose accessible branches are a SET of more than one (a user assigned to several
     * specific branches, without the unrestricted {@code VIEW_ALL_BRANCHES} permission - see
     * {@code BranchAccessService}). Same branch/legacy-fallback semantics as the single-branch
     * query above, just against an {@code IN} list. */
    @Query("SELECT o FROM Order o WHERE o.status NOT IN :excludedStatuses AND ("
            + "(o.branch IS NOT NULL AND o.branch.id IN :branchIds) "
            + "OR (o.branch IS NULL AND (o.table IS NULL OR o.table.floor.branch.id IN :branchIds))"
            + ") ORDER BY o.priority DESC, o.createdAt ASC")
    List<Order> findByStatusNotInAndBranchIn(@Param("excludedStatuses") List<OrderStatus> excludedStatuses, @Param("branchIds") java.util.Collection<UUID> branchIds);

    /** At most one of these should ever exist per table - enforced in OrderService, not just queried (requirement §2: no duplicate orders). */
    Optional<Order> findFirstByTableIdAndStatusNotIn(UUID tableId, List<OrderStatus> excludedStatuses);

    List<Order> findByUpdatedAtAfter(LocalDateTime since);

    List<Order> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    Optional<Order> findByOrderNumber(String orderNumber);

    /** Bills that are out but not fully settled - Round 8 Due Payment Management. */
    List<Order> findByPaymentStatusInAndBilledAtIsNotNullOrderByBilledAtAsc(Collection<PaymentStatus> statuses);

    /** Round 13 (AI Backbone Addendum F1.1/F1.7): the Z-Report's definition of "today's sales" -
     * every order billed within the business date, regardless of when payment was actually
     * collected (a due/partially-paid bill still counts as that day's sale). */
    List<Order> findByBilledAtBetween(LocalDateTime from, LocalDateTime to);

    /** Final round - data-retention auto-purge candidate query: fully-paid orders billed before a
     * cutoff. {@code DataRetentionService} still separately checks each candidate's business date
     * has an EOD session already FINALIZED before actually deleting it - this query alone doesn't
     * guarantee that, since {@code EodSession} is a different table. */
    List<Order> findByBilledAtBeforeAndPaymentStatus(LocalDateTime cutoff, PaymentStatus paymentStatus);
}
