package com.chefpay.server.kot;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.OrderItemRepository;
import com.chefpay.core.repository.OrderRepository;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only KOT (Kitchen Order Ticket) listing (Round 8) - groups {@link OrderItem} rows that
 * share one {@code kotNumber} (one "send to kitchen" action) back into a single ticket, newest
 * first.
 *
 * <p>Bistrodesk branch-isolation release: this previously queried across the whole install with
 * no branch filter at all - same class of leak {@code KitchenService} had. {@link
 * #listRecentTickets} now filters by the caller's accessible branches before grouping, using the
 * same null-tolerant "legacy branchless order is always visible" convention as {@code
 * KitchenService#isVisible}.
 */
@Service
@RequiredArgsConstructor
public class KotTicketService {

    private final OrderItemRepository orderItemRepository;
    private final BranchAccessService branchAccessService;
    private final AppUserRepository appUserRepository;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<KotDtos.KotTicketDto> listRecentTickets(UUID actorUserId) {
        List<OrderItem> items = orderItemRepository.findTop200ByKotNumberNotNullOrderByKotNumberDesc();

        AppUser requester = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        Set<UUID> accessibleBranchIds = branchAccessService.accessibleBranchIds(requester);
        if (accessibleBranchIds != null) {
            items = items.stream().filter(item -> isVisible(item.getOrder(), accessibleBranchIds)).toList();
        }

        // Already sorted descending by kotNumber; a LinkedHashMap built by iterating in that order
        // preserves it - plain Collectors.groupingBy would fall back to HashMap and lose the order.
        Map<Long, List<OrderItem>> byKotNumber = new LinkedHashMap<>();
        for (OrderItem item : items) {
            byKotNumber.computeIfAbsent(item.getKotNumber(), k -> new ArrayList<>()).add(item);
        }

        return byKotNumber.entrySet().stream()
                .map(entry -> toTicketDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private boolean isVisible(Order order, Set<UUID> accessibleBranchIds) {
        Branch branch = order.getEffectiveBranch();
        return branch == null || accessibleBranchIds.contains(branch.getId());
    }

    private KotDtos.KotTicketDto toTicketDto(long kotNumber, List<OrderItem> groupItems) {
        OrderItem first = groupItems.get(0);
        Order order = first.getOrder();
        return new KotDtos.KotTicketDto(
                kotNumber,
                order.getId(),
                order.getOrderNumber(),
                order.getTable() == null ? null : order.getTable().getName(),
                order.getOrderType().name(),
                first.getSentAt(),
                groupItems.stream().map(this::toItemDto).toList()
        );
    }

    private KotDtos.KotTicketItemDto toItemDto(OrderItem item) {
        return new KotDtos.KotTicketItemDto(
                item.getId(),
                item.getMenuItem().getName(),
                item.getQuantity(),
                item.getStatus().name(),
                item.getSpecialInstructions()
        );
    }

    /** POS patch (manual KOT print and order completion): builds a plain-text kitchen ticket
     * directly from an order's CURRENT items - unlike {@link #listRecentTickets}, this is not
     * grouped by {@code kotNumber} and does not require the order to have ever been sent to the
     * kitchen (the whole point of this workflow is printing one without sending). Only usable when
     * the order's own branch has {@code Branch#manualKotPrintEnabled} turned on - see that field's
     * javadoc; every other branch keeps using the existing send-to-kitchen + KDS flow, where this
     * method is simply never called. Mirrors the exact plain-text shape the JavaFX desktop client's
     * own {@code ReceiptPrinter#buildKotText} already produces (same section order, same "qty x
     * name" + indented "* note" convention) so a KOT looks identical regardless of which client
     * printed it. */
    @Transactional(readOnly = true)
    public String buildManualKotText(UUID orderId, UUID actorUserId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        Branch branch = order.getEffectiveBranch();
        if (branch != null) {
            AppUser requester = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
            branchAccessService.assertAccess(requester, branch.getId());
        }
        if (branch == null || !branch.isManualKotPrintEnabled()) {
            throw ApiException.badRequest("MANUAL_KOT_PRINT_DISABLED",
                    "Manual KOT print is not enabled for this order's branch.");
        }
        return buildKotText(order);
    }

    private String buildKotText(Order order) {
        int w = 40;
        StringBuilder sb = new StringBuilder();
        sb.append(center("KITCHEN ORDER TICKET", w)).append('\n');
        sb.append("-".repeat(w)).append('\n');
        sb.append("Order: ").append(order.getOrderNumber()).append('\n');
        sb.append("Type: ").append(order.getOrderType() == null ? "-" : order.getOrderType().name().replace('_', ' ')).append('\n');
        if (order.getTable() != null) {
            sb.append("Table: ").append(order.getTable().getName()).append('\n');
        }
        if (order.getCustomerName() != null) {
            sb.append("Customer: ").append(order.getCustomerName())
                    .append(order.getCustomerPhone() != null ? " (" + order.getCustomerPhone() + ")" : "").append('\n');
        }
        if (order.getCreatedAt() != null) {
            sb.append("Time: ").append(order.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a"))).append('\n');
        }
        sb.append("-".repeat(w)).append('\n');
        for (OrderItem item : order.getItems()) {
            if (item.getStatus() == OrderItemStatus.CANCELLED || item.getStatus() == OrderItemStatus.VOIDED) {
                continue;
            }
            sb.append(item.getQuantity().stripTrailingZeros().toPlainString()).append(" x ").append(item.getMenuItem().getName());
            if (item.isPriority()) {
                sb.append("  [PRIORITY]");
            }
            sb.append('\n');
            if (item.getSpecialInstructions() != null && !item.getSpecialInstructions().isBlank()) {
                sb.append("   * ").append(item.getSpecialInstructions()).append('\n');
            }
        }
        sb.append("-".repeat(w)).append('\n');
        if (order.getNotes() != null && !order.getNotes().isBlank()) {
            sb.append("Notes: ").append(order.getNotes()).append('\n');
            sb.append("-".repeat(w)).append('\n');
        }
        return sb.toString();
    }

    private String center(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat((width - text.length()) / 2) + text;
    }
}
