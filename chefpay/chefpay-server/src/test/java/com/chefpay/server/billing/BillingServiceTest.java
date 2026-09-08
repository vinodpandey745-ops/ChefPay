package com.chefpay.server.billing;

import com.chefpay.core.domain.*;
import com.chefpay.core.repository.*;
import com.chefpay.core.service.AuditService;
import com.chefpay.core.service.NumberGeneratorService;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.fraud.FraudRuleEngineService;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the money math and state-machine guards that matter most in {@link BillingService}:
 * discount application/capping, tax computed proportionally on the post-discount subtotal, split
 * payments driving BILLED -> PAYMENT_PENDING -> PAID, cash change calculation, and the guards that
 * reject overpayment and voiding a payment after the order has already settled.
 */
@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private TaxRepository taxRepository;
    @Mock private DiscountRepository discountRepository;
    @Mock private MenuCategoryRepository menuCategoryRepository;
    @Mock private CashMovementRepository cashMovementRepository;
    @Mock private RestaurantRepository restaurantRepository;
    @Mock private RestaurantTableRepository tableRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private NumberGeneratorService numberGeneratorService;
    @Mock private AuditService auditService;
    @Mock private WebSocketEventPublisher eventPublisher;
    // Round 13: BillingService.recordPayment now calls out to the fraud rule engine on every CASH
    // payment (see that method's javadoc) - a plain Mockito mock is all every test here needs, since
    // BillingService itself already wraps the call in a try/catch (a rule-engine failure must never
    // break the underlying payment) and none of these tests assert anything about fraud detection.
    @Mock private FraudRuleEngineService fraudRuleEngineService;
    // Bistrodesk Phase 3 (requirement #6): BillingService now resolves Tax/Discount branch scope
    // via these two - unused by the money-math tests below (their orders/tax rows never set a
    // branch, so BillingService's branch-aware lookups all resolve through the *-BranchIsNull
    // repository finders instead of ever calling into these), but still required to construct the
    // service with @RequiredArgsConstructor's now-longer field list.
    @Mock private BranchRepository branchRepository;
    @Mock private BranchAccessService branchAccessService;

    private BillingService billingService;
    private MenuCategory category;
    private AppUser cashier;

    @BeforeEach
    void setUp() {
        // Bistrodesk Phase 6 (bug #12): the tax-bracket math these tests exercise ("tax computed
        // proportionally on the post-discount subtotal", per this class's own javadoc) moved out of
        // BillingService into TaxCalculationService - a REAL instance wrapping the same mocked
        // taxRepository these tests already stub, so every existing stub/assertion below keeps
        // working unchanged; only the object that actually calls taxRepository is different now.
        TaxCalculationService taxCalculationService = new TaxCalculationService(taxRepository);
        billingService = new BillingService(orderRepository, paymentRepository, taxRepository, discountRepository,
                menuCategoryRepository, cashMovementRepository, restaurantRepository, tableRepository, appUserRepository,
                numberGeneratorService, auditService, eventPublisher, fraudRuleEngineService, branchRepository, branchAccessService,
                taxCalculationService);
        category = MenuCategory.builder().name("Mains").build();
        setId(category, UUID.randomUUID());
        cashier = AppUser.builder().username("cashier1").displayName("Cashier One")
                .passwordHash("x").role(Role.builder().name("CASHIER").permissions(new java.util.HashSet<>()).build()).build();
        setId(cashier, UUID.randomUUID());
    }

    @Test
    void percentageDiscountIsAppliedToSubtotal() {
        Order order = orderWithSubtotal(new BigDecimal("1000.00"), OrderStatus.SERVED);
        stubOrderPersistence(order);
        stubEmptyPayments(order.getId());
        stubRestaurantWithNoServiceCharge();

        BillingDtos.BillDto bill = billingService.applyDiscount(order.getId(), null, "PERCENTAGE",
                new BigDecimal("10"), "loyalty", order.getVersion(), cashier.getId());

        assertThat(bill.discountAmount()).isEqualByComparingTo("100.00");
        assertThat(bill.totalAmount()).isEqualByComparingTo("900.00");
        assertThat(order.getDiscountReason()).contains("loyalty");
    }

    @Test
    void fixedAmountDiscountIsCappedAtSubtotalSoTotalNeverGoesNegative() {
        Order order = orderWithSubtotal(new BigDecimal("50.00"), OrderStatus.SERVED);
        stubOrderPersistence(order);
        stubEmptyPayments(order.getId());
        stubRestaurantWithNoServiceCharge();

        BillingDtos.BillDto bill = billingService.applyDiscount(order.getId(), null, "FIXED_AMOUNT",
                new BigDecimal("500.00"), "manual override", order.getVersion(), cashier.getId());

        assertThat(bill.discountAmount()).isEqualByComparingTo("50.00");
        assertThat(bill.totalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void discountRejectedOnceOrderIsPastBillRequested() {
        Order order = orderWithSubtotal(new BigDecimal("100.00"), OrderStatus.BILLED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> billingService.applyDiscount(order.getId(), null, "PERCENTAGE",
                BigDecimal.TEN, "late", order.getVersion(), cashier.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Served or Bill Requested");
    }

    @Test
    void generateBillComputesTaxProportionallyOnPostDiscountSubtotalPlusServiceCharge() {
        Tax gst = Tax.builder().name("GST").code("GST").ratePercent(new BigDecimal("10")).build();
        setId(gst, UUID.randomUUID());
        Tax defaultTax = Tax.builder().name("VAT").code("VAT").ratePercent(new BigDecimal("5")).defaultRate(true).build();
        setId(defaultTax, UUID.randomUUID());

        MenuItem taxedItem = MenuItem.builder().category(category).name("Taxed").price(BigDecimal.TEN).taxCode("GST").build();
        setId(taxedItem, UUID.randomUUID());
        MenuItem defaultTaxedItem = MenuItem.builder().category(category).name("Default").price(BigDecimal.TEN).build();
        setId(defaultTaxedItem, UUID.randomUUID());

        Order order = Order.builder().orderNumber("ORD-1").orderType(OrderType.DINE_IN)
                .status(OrderStatus.BILL_REQUESTED)
                .subtotal(new BigDecimal("1000.00")).discountAmount(new BigDecimal("100.00")).build();
        setId(order, UUID.randomUUID());
        order.getItems().add(item(order, taxedItem, new BigDecimal("600.00")));
        order.getItems().add(item(order, defaultTaxedItem, new BigDecimal("400.00")));

        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        // This order has no branch (no table, no Order.branch set), so BillingService#resolveTax/
        // #resolveDefaultTax resolve with a null branchId - i.e. through the *-BranchIsNull finders,
        // exactly the pre-Phase-3 single-tier (global-only) lookups these used to be.
        when(taxRepository.findByCodeAndBranchIsNull("GST")).thenReturn(Optional.of(gst));
        when(taxRepository.findByActiveTrueAndDefaultRateTrueAndBranchIsNull()).thenReturn(Optional.of(defaultTax));

        Restaurant restaurant = Restaurant.builder().name("Test Restaurant").serviceChargePercent(new BigDecimal("5")).build();
        setId(restaurant, UUID.randomUUID());
        when(restaurantRepository.findAll()).thenReturn(List.of(restaurant));
        stubEmptyPayments(order.getId());

        BillingDtos.BillDto bill = billingService.generateBill(order.getId(), order.getVersion(), null, cashier.getId());

        // taxableBase = 1000 - 100 = 900; GST bracket 600/1000*900=540 @10% = 54.00;
        // default bracket 400/1000*900=360 @5% = 18.00; total tax = 72.00
        assertThat(bill.taxAmount()).isEqualByComparingTo("72.00");
        // service charge = 900 * 5% = 45.00
        assertThat(bill.serviceChargeAmount()).isEqualByComparingTo("45.00");
        // total = 1000 - 100 + 72 + 45 = 1017.00
        assertThat(bill.totalAmount()).isEqualByComparingTo("1017.00");
        assertThat(bill.orderStatus()).isEqualTo("BILLED");
        assertThat(order.getBilledAt()).isNotNull();
    }

    @Test
    void generateBillRejectedWhenOrderIsNotYetBillRequested() {
        Order order = orderWithSubtotal(new BigDecimal("100.00"), OrderStatus.SERVED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> billingService.generateBill(order.getId(), order.getVersion(), null, cashier.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be Bill Requested first");
    }

    @Test
    void splitCashPaymentsDriveOrderThroughPaymentPendingToPaidWithCorrectChange() {
        Order order = billedOrder(new BigDecimal("1000.00"));
        List<Payment> savedPayments = new ArrayList<>();
        stubOrderPersistence(order);
        when(paymentRepository.findByOrderIdOrderByReceivedAtAsc(order.getId())).thenAnswer(inv -> new ArrayList<>(savedPayments));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            setId(p, UUID.randomUUID());
            savedPayments.add(p);
            return p;
        });
        when(appUserRepository.findById(cashier.getId())).thenReturn(Optional.of(cashier));
        when(numberGeneratorService.next("RCPT")).thenReturn("RCPT-1").thenReturn("RCPT-2");

        // First tender: cash, guest hands over 600 against a 1000 balance - fully applied, no change.
        BillingDtos.BillDto afterFirst = billingService.recordPayment(order.getId(), "CASH", null,
                new BigDecimal("600.00"), null, null, order.getVersion(), cashier.getId());
        assertThat(afterFirst.amountPaid()).isEqualByComparingTo("600.00");
        assertThat(afterFirst.balanceDue()).isEqualByComparingTo("400.00");
        assertThat(afterFirst.orderStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(afterFirst.paymentStatus()).isEqualTo("PARTIALLY_PAID");

        // Second tender: cash, guest hands over 500 against the remaining 400 - only 400 applies, 100 change.
        BillingDtos.BillDto afterSecond = billingService.recordPayment(order.getId(), "CASH", null,
                new BigDecimal("500.00"), null, null, order.getVersion(), cashier.getId());
        assertThat(afterSecond.amountPaid()).isEqualByComparingTo("1000.00");
        assertThat(afterSecond.balanceDue()).isEqualByComparingTo("0.00");
        assertThat(afterSecond.orderStatus()).isEqualTo("PAID");
        assertThat(afterSecond.paymentStatus()).isEqualTo("PAID");
        assertThat(order.getPaidAt()).isNotNull();

        BillingDtos.PaymentDto lastPayment = afterSecond.payments().get(afterSecond.payments().size() - 1);
        assertThat(lastPayment.amount()).isEqualByComparingTo("400.00");
        assertThat(lastPayment.changeAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void cardPaymentExceedingBalanceIsRejected() {
        Order order = billedOrder(new BigDecimal("500.00"));
        // Only findById is needed here (not the full stubOrderPersistence, which also stubs
        // orderRepository.save) - the AMOUNT_EXCEEDS_BALANCE guard throws before recordPayment
        // ever reaches its orderRepository.save call, and Mockito's strict stubs correctly flag
        // an unused save() stub as unnecessary.
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        stubEmptyPayments(order.getId());
        when(appUserRepository.findById(cashier.getId())).thenReturn(Optional.of(cashier));

        assertThatThrownBy(() -> billingService.recordPayment(order.getId(), "CARD", new BigDecimal("600.00"),
                null, "TXN123", null, order.getVersion(), cashier.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("exceeds the remaining balance");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void paymentRejectedBeforeOrderIsBilled() {
        Order order = orderWithSubtotal(new BigDecimal("100.00"), OrderStatus.SERVED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> billingService.recordPayment(order.getId(), "CASH", null,
                new BigDecimal("100.00"), null, null, order.getVersion(), cashier.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be billed");
    }

    @Test
    void voidingAPaymentAfterTheOrderIsFullyPaidIsRejected() {
        Order order = billedOrder(new BigDecimal("100.00"));
        order.setStatus(OrderStatus.PAID);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> billingService.voidPayment(order.getId(), UUID.randomUUID(), "mistake",
                order.getVersion(), cashier.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only be voided before");
    }

    @Test
    void presetDiscountIsCappedByItsOwnMaxDiscountAmountEvenWhenThePercentCalculationExceedsIt() {
        Order order = orderWithSubtotal(new BigDecimal("1000.00"), OrderStatus.SERVED);
        stubOrderPersistence(order);
        stubEmptyPayments(order.getId());
        stubRestaurantWithNoServiceCharge();

        Discount preset = Discount.builder().name("Big Sale").type(DiscountType.PERCENTAGE)
                .value(new BigDecimal("50")).maxDiscountAmount(new BigDecimal("200.00")).build();
        setId(preset, UUID.randomUUID());
        when(discountRepository.findById(preset.getId())).thenReturn(Optional.of(preset));

        // Uncapped, 50% of 1000.00 would waive 500.00 - the preset's own 200.00 ceiling must win.
        BillingDtos.BillDto bill = billingService.applyDiscount(order.getId(), preset.getId(), null,
                null, null, order.getVersion(), cashier.getId());

        assertThat(bill.discountAmount()).isEqualByComparingTo("200.00");
        assertThat(bill.totalAmount()).isEqualByComparingTo("800.00");
    }

    @Test
    void categoryScopedPresetOnlyDiscountsItsOwnCategorysSliceOfTheSubtotal() {
        MenuCategory beverages = MenuCategory.builder().name("Beverages").build();
        setId(beverages, UUID.randomUUID());

        Order order = Order.builder().orderNumber("ORD-" + UUID.randomUUID()).orderType(OrderType.DINE_IN)
                .status(OrderStatus.SERVED).subtotal(new BigDecimal("1000.00")).build();
        setId(order, UUID.randomUUID());
        MenuItem food = MenuItem.builder().category(category).name("Food").price(new BigDecimal("600.00")).build();
        setId(food, UUID.randomUUID());
        MenuItem beverage = MenuItem.builder().category(beverages).name("Soda").price(new BigDecimal("400.00")).build();
        setId(beverage, UUID.randomUUID());
        order.getItems().add(item(order, food, new BigDecimal("600.00")));
        order.getItems().add(item(order, beverage, new BigDecimal("400.00")));

        stubOrderPersistence(order);
        stubEmptyPayments(order.getId());
        stubRestaurantWithNoServiceCharge();

        Discount preset = Discount.builder().name("Beverages 10% Off").type(DiscountType.PERCENTAGE)
                .value(new BigDecimal("10")).applicableCategory(beverages).build();
        setId(preset, UUID.randomUUID());
        when(discountRepository.findById(preset.getId())).thenReturn(Optional.of(preset));

        BillingDtos.BillDto bill = billingService.applyDiscount(order.getId(), preset.getId(), null,
                null, null, order.getVersion(), cashier.getId());

        // Only the 400.00 beverages slice is discountable: 10% of 400.00 = 40.00, not 10% of the
        // whole 1000.00 subtotal (which would be 100.00).
        assertThat(bill.discountAmount()).isEqualByComparingTo("40.00");
        assertThat(bill.totalAmount()).isEqualByComparingTo("960.00");
    }

    @Test
    void backToServedRevertsABilledOrderWithNoPaymentsBackToServed() {
        Order order = billedOrder(new BigDecimal("1000.00"));
        stubOrderPersistence(order);
        stubEmptyPayments(order.getId());
        stubRestaurantWithNoServiceCharge();

        BillingDtos.BillDto bill = billingService.backToServed(order.getId(), order.getVersion(), cashier.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SERVED);
        assertThat(bill.orderStatus()).isEqualTo("SERVED");
        assertThat(order.getBilledAt()).isNull();
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(order.getServiceChargeAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void backToServedRejectedOnceAPaymentHasBeenRecorded() {
        Order order = billedOrder(new BigDecimal("1000.00"));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        Payment existingPayment = Payment.builder().order(order).method(PaymentMethod.CASH)
                .amount(new BigDecimal("500.00")).build();
        setId(existingPayment, UUID.randomUUID());
        when(paymentRepository.findByOrderIdOrderByReceivedAtAsc(order.getId())).thenReturn(List.of(existingPayment));

        assertThatThrownBy(() -> billingService.backToServed(order.getId(), order.getVersion(), cashier.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already has a payment");
    }

    @Test
    void splitBillEvenlyPutsTheRoundingRemainderOnTheLastShare() {
        Order order = orderWithSubtotal(new BigDecimal("100.00"), OrderStatus.SERVED);
        order.setTotalAmount(new BigDecimal("100.00"));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        BillingDtos.SplitBillResponse split = billingService.splitBillEvenly(order.getId(), 3);

        assertThat(split.shares()).hasSize(3);
        assertThat(split.shares().get(0)).isEqualByComparingTo("33.33");
        assertThat(split.shares().get(1)).isEqualByComparingTo("33.33");
        assertThat(split.shares().get(2)).isEqualByComparingTo("33.34");
        BigDecimal sum = split.shares().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(split.totalAmount());
    }

    // ---- fixtures ----

    /**
     * {@code Order.recalculateTotals()} (called by both {@code applyDiscount} and
     * {@code generateBill}) recomputes {@code subtotal} from the live items list rather than
     * trusting a caller-set value - so every fixture needs a real item whose lineTotal matches the
     * intended subtotal, exactly like a real order would after Phase 2's OrderService kept them in
     * sync. Skipping this would let a stale subtotal silently reset to zero mid-test.
     */
    private Order orderWithSubtotal(BigDecimal subtotal, OrderStatus status) {
        Order order = Order.builder().orderNumber("ORD-" + UUID.randomUUID()).orderType(OrderType.DINE_IN)
                .status(status).subtotal(subtotal).build();
        setId(order, UUID.randomUUID());
        MenuItem menuItem = MenuItem.builder().category(category).name("Test Item").price(subtotal).build();
        setId(menuItem, UUID.randomUUID());
        order.getItems().add(item(order, menuItem, subtotal));
        return order;
    }

    private Order billedOrder(BigDecimal totalAmount) {
        Order order = Order.builder().orderNumber("ORD-" + UUID.randomUUID()).orderType(OrderType.DINE_IN)
                .status(OrderStatus.BILLED).subtotal(totalAmount).totalAmount(totalAmount).build();
        setId(order, UUID.randomUUID());
        return order;
    }

    private OrderItem item(Order order, MenuItem menuItem, BigDecimal lineTotal) {
        OrderItem item = OrderItem.builder().order(order).menuItem(menuItem)
                .quantity(BigDecimal.ONE).unitPriceSnapshot(lineTotal).status(OrderItemStatus.SERVED).build();
        setId(item, UUID.randomUUID());
        return item;
    }

    private void stubOrderPersistence(Order order) {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubEmptyPayments(UUID orderId) {
        when(paymentRepository.findByOrderIdOrderByReceivedAtAsc(orderId)).thenReturn(List.of());
    }

    /**
     * {@code toBillDto} looks up the restaurant for its live pre-bill tax/service-charge preview
     * (see that method's javadoc - Round 7 review pass) whenever the order is still in a
     * discount-editable status (SERVED/BILL_REQUESTED). Only the tests that exercise that path need
     * this stub - adding it in {@code setUp()} instead would trip Mockito's strict-stubs
     * UnnecessaryStubbingException on every other test that never reaches {@code currentRestaurant()}.
     * {@code serviceChargePercent} defaults to {@link BigDecimal#ZERO} (matching
     * {@code Restaurant}'s own {@code @Builder.Default}) so these tests' pre-existing total-amount
     * assertions stay correct without needing a service-charge term added to their expected values.
     */
    private void stubRestaurantWithNoServiceCharge() {
        Restaurant restaurant = Restaurant.builder().name("Test Restaurant").build();
        setId(restaurant, UUID.randomUUID());
        when(restaurantRepository.findAll()).thenReturn(List.of(restaurant));
    }

    private void setId(BaseEntity entity, UUID id) {
        entity.setId(id);
    }
}
