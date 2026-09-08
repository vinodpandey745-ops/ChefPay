package com.chefpay.server.kitchen;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.KitchenStation;
import com.chefpay.core.domain.MenuCategory;
import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import com.chefpay.core.domain.OrderType;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.AppUserRepository;
import com.chefpay.core.repository.KitchenStationRepository;
import com.chefpay.core.repository.OrderItemRepository;
import com.chefpay.core.repository.OrderRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.core.service.AuditService;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.orders.OrderDtos;
import com.chefpay.server.orders.OrderMapper;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KitchenService#listQueue} does the real work in this module (grouping item-level rows
 * back into per-order tickets, and optional station filtering) - {@link OrderItemRepository} and
 * {@link KitchenStationRepository} are the only collaborators worth mocking since the FIFO
 * ordering guarantee comes from the repository query itself, which this test simulates by
 * returning items pre-sorted oldest-{@code sentAt}-first, exactly as
 * {@code findByStatusInOrderBySentAtAsc} is specified to.
 */
@ExtendWith(MockitoExtension.class)
class KitchenServiceTest {

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private KitchenStationRepository stationRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private WebSocketEventPublisher eventPublisher;

    @Mock
    private BranchAccessService branchAccessService;

    @Mock
    private AppUserRepository appUserRepository;

    // OrderMapper has no dependencies of its own, so a real instance is simpler and safer than a mock.
    private final OrderMapper orderMapper = new OrderMapper();

    private KitchenService kitchenService;

    private MenuCategory category;

    @BeforeEach
    void setUp() {
        kitchenService = new KitchenService(orderItemRepository, orderRepository, stationRepository, restaurantRepository,
                orderMapper, auditService, eventPublisher, branchAccessService, appUserRepository);
        category = MenuCategory.builder().name("Mains").displayOrder(0).build();
    }

    @Test
    void groupsItemsIntoOneTicketPerOrderPreservingOldestFirstOrder() {
        Order orderA = order("ORD-A");
        Order orderB = order("ORD-B");

        // Repository is asked to return oldest-sentAt-first; orderB's item is older even though
        // orderA is listed first below, so the ticket order below must follow sentAt, not object order.
        OrderItem olderItemOnOrderB = item(orderB, null, OrderItemStatus.SENT, LocalDateTime.now().minusMinutes(20));
        OrderItem newerItemOnOrderA = item(orderA, null, OrderItemStatus.PREPARING, LocalDateTime.now().minusMinutes(2));
        orderB.getItems().add(olderItemOnOrderB);
        orderA.getItems().add(newerItemOnOrderA);

        when(orderItemRepository.findByStatusInOrderBySentAtAsc(any()))
                .thenReturn(List.of(olderItemOnOrderB, newerItemOnOrderA));
        stubNoSingleWorkingBranch();

        List<OrderDtos.OrderDto> tickets = kitchenService.listQueue(null, null, null);

        assertThat(tickets).hasSize(2);
        assertThat(tickets.get(0).orderNumber()).isEqualTo("ORD-B");
        assertThat(tickets.get(0).items()).hasSize(1);
        assertThat(tickets.get(1).orderNumber()).isEqualTo("ORD-A");
        assertThat(tickets.get(1).items()).hasSize(1);
    }

    @Test
    void multipleActiveLinesOnTheSameOrderLandOnOneTicket() {
        Order order = order("ORD-C");
        OrderItem first = item(order, null, OrderItemStatus.SENT, LocalDateTime.now().minusMinutes(10));
        OrderItem second = item(order, null, OrderItemStatus.ACCEPTED, LocalDateTime.now().minusMinutes(5));
        order.getItems().add(first);
        order.getItems().add(second);

        when(orderItemRepository.findByStatusInOrderBySentAtAsc(any()))
                .thenReturn(List.of(first, second));
        stubNoSingleWorkingBranch();

        List<OrderDtos.OrderDto> tickets = kitchenService.listQueue(null, null, null);

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).items()).hasSize(2);
    }

    @Test
    void stationFilterExcludesNonMatchingItemsAndDropsOrdersLeftWithNone() {
        KitchenStation grill = KitchenStation.builder().name("Grill").build();
        setId(grill, UUID.randomUUID());
        KitchenStation cold = KitchenStation.builder().name("Cold").build();
        setId(cold, UUID.randomUUID());

        Order grillOrder = order("ORD-GRILL");
        Order coldOrder = order("ORD-COLD");
        OrderItem grillItem = item(grillOrder, grill, OrderItemStatus.SENT, LocalDateTime.now().minusMinutes(3));
        OrderItem coldItem = item(coldOrder, cold, OrderItemStatus.SENT, LocalDateTime.now().minusMinutes(3));
        grillOrder.getItems().add(grillItem);
        coldOrder.getItems().add(coldItem);

        when(orderItemRepository.findByStatusInOrderBySentAtAsc(any()))
                .thenReturn(List.of(grillItem, coldItem));
        stubNoSingleWorkingBranch();

        List<OrderDtos.OrderDto> tickets = kitchenService.listQueue(null, grill.getId(), null);

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).orderNumber()).isEqualTo("ORD-GRILL");
    }

    @Test
    void itemsWithoutAStationAreExcludedWhenFilteringByStation() {
        KitchenStation grill = KitchenStation.builder().name("Grill").build();
        setId(grill, UUID.randomUUID());

        Order unroutedOrder = order("ORD-UNROUTED");
        OrderItem unroutedItem = item(unroutedOrder, null, OrderItemStatus.SENT, LocalDateTime.now());
        unroutedOrder.getItems().add(unroutedItem);

        when(orderItemRepository.findByStatusInOrderBySentAtAsc(any()))
                .thenReturn(List.of(unroutedItem));
        stubNoSingleWorkingBranch();

        List<OrderDtos.OrderDto> tickets = kitchenService.listQueue(null, grill.getId(), null);

        assertThat(tickets).isEmpty();
    }

    @Test
    void listQueueOnlyQueriesActiveKitchenStatuses() {
        when(orderItemRepository.findByStatusInOrderBySentAtAsc(any())).thenReturn(List.of());
        stubNoSingleWorkingBranch();

        kitchenService.listQueue(null, null, null);

        Set<OrderItemStatus> expected =
                EnumSet.of(OrderItemStatus.SENT, OrderItemStatus.ACCEPTED, OrderItemStatus.PREPARING, OrderItemStatus.READY);
        verify(orderItemRepository).findByStatusInOrderBySentAtAsc(expected);
    }

    @Test
    void serveAllItemsMarksEveryActiveLineServedWhenSimpleModeIsOn() {
        Order order = order("ORD-SIMPLE");
        OrderItem sentItem = item(order, null, OrderItemStatus.SENT, LocalDateTime.now().minusMinutes(5));
        OrderItem preparingItem = item(order, null, OrderItemStatus.PREPARING, LocalDateTime.now().minusMinutes(3));
        order.getItems().add(sentItem);
        order.getItems().add(preparingItem);

        Restaurant restaurant = Restaurant.builder().name("Test Restaurant").kitchenServiceMode("SIMPLE").build();
        setId(restaurant, UUID.randomUUID());
        when(restaurantRepository.findAll()).thenReturn(List.of(restaurant));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order saved = kitchenService.serveAllItems(order.getId(), order.getVersion(), UUID.randomUUID());

        assertThat(saved.getItems()).allMatch(i -> i.getStatus() == OrderItemStatus.SERVED);
        assertThat(sentItem.getServedAt()).isNotNull();
        assertThat(preparingItem.getServedAt()).isNotNull();
    }

    @Test
    void serveAllItemsRejectedWhenSimpleKitchenModeIsNotEnabled() {
        Order order = order("ORD-DETAILED");
        order.getItems().add(item(order, null, OrderItemStatus.SENT, LocalDateTime.now()));

        Restaurant restaurant = Restaurant.builder().name("Test Restaurant").kitchenServiceMode("DETAILED").build();
        setId(restaurant, UUID.randomUUID());
        when(restaurantRepository.findAll()).thenReturn(List.of(restaurant));

        assertThatThrownBy(() -> kitchenService.serveAllItems(order.getId(), order.getVersion(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Simple Serve All");
    }

    private Order order(String orderNumber) {
        Order order = Order.builder()
                .orderNumber(orderNumber)
                .orderType(OrderType.DINE_IN)
                .build();
        setId(order, UUID.randomUUID());
        return order;
    }

    private OrderItem item(Order order, KitchenStation station, OrderItemStatus status, LocalDateTime sentAt) {
        MenuItem menuItem = MenuItem.builder()
                .category(category)
                .name("Test Item")
                .price(BigDecimal.TEN)
                .station(station)
                .build();
        setId(menuItem, UUID.randomUUID());

        OrderItem item = OrderItem.builder()
                .order(order)
                .menuItem(menuItem)
                .quantity(BigDecimal.ONE)
                .unitPriceSnapshot(BigDecimal.TEN)
                .status(status)
                .sentAt(sentAt)
                .build();
        setId(item, UUID.randomUUID());
        return item;
    }

    /** BaseEntity.id has no builder setter (it's @GeneratedValue) - tests need a stable id for map-keying/equality. */
    private void setId(com.chefpay.core.domain.BaseEntity entity, UUID id) {
        entity.setId(id);
    }

    /** Bistrodesk post-release fix: {@code listQueue} (with no explicit {@code branchId}) now calls
     * {@link BranchAccessService#resolveEffectiveBranchId} before falling back to {@link
     * BranchAccessService#accessibleBranchIds} - see that method's own javadoc. None of these tests
     * exercise branch scoping itself (they're testing grouping/station-filtering), so this stub makes
     * the mocked {@code resolveEffectiveBranchId} throw the same {@code BRANCH_REQUIRED} a real
     * unrestricted-caller-with-no-default-branch would, landing back on the unstubbed (so
     * null-returning, i.e. "no filter") {@code accessibleBranchIds} - reproducing this class's
     * original "no branch filter" behavior exactly, without a real {@link
     * com.chefpay.core.domain.AppUser}. */
    private void stubNoSingleWorkingBranch() {
        // BranchAccessService overloads resolveEffectiveBranchId for (AppUser, UUID) and
        // (AuthenticatedPrincipal, UUID) - KitchenService's resolveBranchIds always calls the AppUser
        // overload (it already resolved actorUserId to an AppUser itself), but a bare any() can't
        // tell the two overloads apart and javac rejects the call as ambiguous. any(AppUser.class)
        // pins it to the one overload actually being stubbed.
        when(branchAccessService.resolveEffectiveBranchId(any(AppUser.class), any()))
                .thenThrow(ApiException.badRequest("BRANCH_REQUIRED", "test stub - no single branch to resolve"));
    }
}
