package com.chefpay.server.billing;

import com.chefpay.core.domain.*;
import com.chefpay.core.repository.*;
import com.chefpay.core.service.AuditService;
import com.chefpay.core.service.NumberGeneratorService;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.CorrelationIdHolder;
import com.chefpay.server.fraud.FraudRuleContext;
import com.chefpay.server.fraud.FraudRuleEngineService;
import com.chefpay.server.fraud.FraudRuleEventType;
import com.chefpay.server.fraud.NoSaleFrequencyRule;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tax, Discount, Billing, Split Bill, Payments, Receipt and Cash Management - Phase 4
 * (ARCHITECTURE.md §13). Reuses {@link Order}'s existing money columns (subtotal/discountAmount/
 * taxAmount/serviceChargeAmount/tipAmount/totalAmount) and {@code recalculateTotals()} rather than
 * introducing a parallel "Bill" aggregate - the order already carries the numbers Phase 1-3 have
 * been maintaining, this module is what finally computes the tax/discount/service-charge pieces
 * for real instead of leaving them at zero.
 *
 * <p>Money flow through one order's lifecycle: {@link #applyDiscount} may run any number of times
 * while the order is SERVED/BILL_REQUESTED (pre-bill, discount still editable);
 * {@link #generateBill} freezes tax + service charge (computed on the discounted subtotal) and
 * moves the order to BILLED, after which discount is no longer editable (mirrors the existing
 * "items frozen once billed" rule from Phase 2); {@link #recordPayment} accepts one or more
 * tenders (split bill) against the frozen total until the balance reaches zero, advancing the
 * order through PAYMENT_PENDING to PAID; {@link #voidPayment} is a correction path available only
 * before the order reaches PAID, matching the state machine's forward-only design (no path back
 * from PAID without going through Phase 5+ refund handling).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingService {

    /** POS patch: discount was previously only offered once an order reached Served/Bill
     * Requested, which the UI surfaced as "Available once order is served" and left the discount
     * option looking permanently disabled for most of an order's life - reported as "the discount
     * is not being applied... option is disabled in UI, please enable it". Discount is only frozen
     * once the bill is generated (matches {@code OrderService.ITEM_ADDABLE_STATUSES}'s "frozen once
     * billed" rule), so it is now editable at every pre-bill, non-cancelled status - the exact same
     * set {@link OrderStatus}'s own {@code CANCELLABLE_FROM} already uses for "still open, not yet
     * billed". This only widens WHEN a discount can be applied; the discount calculation itself
     * (amount/percentage/preset logic below) is unchanged. */
    private static final Set<OrderStatus> DISCOUNT_EDITABLE_STATUSES = EnumSet.of(OrderStatus.DRAFT,
            OrderStatus.PLACED, OrderStatus.SENT_TO_KITCHEN, OrderStatus.ACCEPTED, OrderStatus.PREPARING,
            OrderStatus.READY, OrderStatus.SERVED, OrderStatus.BILL_REQUESTED);

    /** A payment may be recorded once a bill exists and until the order is fully settled. */
    private static final Set<OrderStatus> PAYABLE_STATUSES = EnumSet.of(OrderStatus.BILLED, OrderStatus.PAYMENT_PENDING);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TaxRepository taxRepository;
    private final DiscountRepository discountRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final CashMovementRepository cashMovementRepository;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantTableRepository tableRepository;
    private final AppUserRepository appUserRepository;
    private final NumberGeneratorService numberGeneratorService;
    private final AuditService auditService;
    private final WebSocketEventPublisher eventPublisher;
    private final FraudRuleEngineService fraudRuleEngineService;
    private final BranchRepository branchRepository;
    private final BranchAccessService branchAccessService;
    // Bistrodesk Phase 6 (bug #12): the tax-bracket math itself now lives in this shared component
    // (extracted, unchanged, from what used to be this class's own private computeTaxLines/
    // resolveTax/resolveDefaultTax) so OrderService can compute the exact same live tax on every
    // pre-checkout cart mutation - see TaxCalculationService's own javadoc for the full story.
    private final TaxCalculationService taxCalculationService;

    // ---- Tax config (RESTAURANT_MANAGE) ----
    //
    // Bistrodesk Phase 3 (requirement #6): Tax.branch is optional - null is the GLOBAL rate for a
    // code (today's only behavior), set overrides that code's rate at one specific branch. At most
    // one row per SCOPE may be defaultRate=true: one global default, and separately one default per
    // branch - so a branch with no default of its own still falls back to the global default rather
    // than having no default at all (see resolveDefaultTax). Deliberately no third "state"/region
    // tier per the confirmed decision to keep this to branch-level overrides only.

    /** Unrestricted (accessibleBranchIds == null) sees every tax, global and every branch's; a
     * restricted caller sees the global rates plus their own branch(es)' overrides - same
     * visibility convention {@code InventoryController}/{@code MenuController} already use. */
    @Transactional(readOnly = true)
    public List<Tax> listTaxes(Set<UUID> accessibleBranchIds) {
        return taxRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(t -> visible(t.getBranch(), accessibleBranchIds))
                .toList();
    }

    /** The "effective config" view (requirement #6's "resolved rule is visible... not just applied
     * silently"): for one branch, the rate that would ACTUALLY apply per code - that branch's own
     * override where one exists, otherwise the global rate. Every code appears at most once. */
    @Transactional(readOnly = true)
    public List<Tax> listEffectiveTaxes(UUID branchId) {
        Map<String, Tax> byCode = new LinkedHashMap<>();
        for (Tax t : taxRepository.findByActiveTrueOrderByNameAsc()) {
            if (t.getBranch() != null && !t.getBranch().getId().equals(branchId)) {
                continue; // another branch's override - never relevant to this branch's effective view
            }
            Tax existing = byCode.get(t.getCode());
            // A branch-specific row always wins over a global one for the same code, regardless of
            // which was iterated first (findByActiveTrueOrderByNameAsc has no scope-aware ordering).
            if (existing == null || (existing.getBranch() == null && t.getBranch() != null)) {
                byCode.put(t.getCode(), t);
            }
        }
        return byCode.values().stream().sorted(Comparator.comparing(Tax::getName)).toList();
    }

    private boolean visible(Branch branch, Set<UUID> accessibleBranchIds) {
        return accessibleBranchIds == null || branch == null || accessibleBranchIds.contains(branch.getId());
    }

    @Transactional
    public Tax createTax(String name, String code, BigDecimal ratePercent, boolean defaultRate, UUID branchId, UUID actorUserId) {
        Branch branch = resolveTaxBranch(branchId, actorUserId);
        boolean duplicate = branch == null
                ? taxRepository.existsByCodeIgnoreCaseAndBranchIsNull(code)
                : taxRepository.existsByCodeIgnoreCaseAndBranchId(code, branch.getId());
        if (duplicate) {
            throw ApiException.badRequest("DUPLICATE_TAX_CODE", "A tax rate with code '" + code + "' already exists"
                    + (branch == null ? " globally." : " at this branch."));
        }
        // Most restaurants configure exactly one tax rate (a single GST %) and expect it to just
        // apply to every bill. Ticking "Apply by default" is an easy-to-miss step on the New Tax
        // dialog, and forgetting it means resolveTax() has no defaultTax to fall back to for any
        // item without an explicit taxCode - the tax silently never applies to any bill, with no
        // error anywhere (this was reported as "GST is calculating properly but not adding to the
        // bill" - the newly created tax was never marked default, so computeTaxLines() had nothing
        // to compute). If this is the very first active tax in this SCOPE (global, or this branch)
        // being configured, make it the default regardless of the checkbox; once a second tax
        // exists in that same scope the checkbox is honored normally.
        boolean scopeHasNoTaxYet = branch == null
                ? taxRepository.findByActiveTrueOrderByNameAsc().stream().noneMatch(t -> t.getBranch() == null)
                : taxRepository.findByActiveTrueOrderByNameAsc().stream()
                        .noneMatch(t -> t.getBranch() != null && t.getBranch().getId().equals(branch.getId()));
        boolean effectiveDefault = defaultRate || scopeHasNoTaxYet;
        if (effectiveDefault) {
            clearExistingDefaultTax(branch == null ? null : branch.getId());
        }
        return taxRepository.save(Tax.builder().name(name).code(code).ratePercent(ratePercent)
                .defaultRate(effectiveDefault).branch(branch).build());
    }

    @Transactional
    public Tax updateTax(UUID id, String name, BigDecimal ratePercent, Boolean active, Boolean defaultRate, long version, UUID actorUserId) {
        Tax tax = taxRepository.findById(id).orElseThrow(() -> ApiException.notFound("Tax rate not found"));
        // Bistrodesk Phase 3: a branch-restricted caller may only manage a global rate's... no -
        // may only manage a tax that's either global or already at one of their own branches. This
        // endpoint doesn't support reassigning a tax's branch after creation (a rate's ownership is
        // a one-time decision, not something edited casually) - only create picks the branch.
        branchAccessService.assertAccess(resolveRequester(actorUserId), tax.getBranch() == null ? null : tax.getBranch().getId());
        if (tax.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(Tax.class, id);
        }
        if (name != null) {
            tax.setName(name);
        }
        if (ratePercent != null) {
            tax.setRatePercent(ratePercent);
        }
        if (active != null) {
            tax.setActive(active);
        }
        UUID scopeBranchId = tax.getBranch() == null ? null : tax.getBranch().getId();
        if (Boolean.TRUE.equals(defaultRate) && !tax.isDefaultRate()) {
            clearExistingDefaultTax(scopeBranchId);
            tax.setDefaultRate(true);
        } else if (Boolean.FALSE.equals(defaultRate)) {
            tax.setDefaultRate(false);
        }
        return taxRepository.save(tax);
    }

    private Branch resolveTaxBranch(UUID branchId, UUID actorUserId) {
        if (branchId == null) {
            return null;
        }
        branchAccessService.assertAccess(resolveRequester(actorUserId), branchId);
        return branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
    }

    /** Same "resolve fresh by id, null-tolerant" pattern {@code OrderService}/{@code
     * InventoryService} already use for their own {@code actorUserId} params - {@code
     * BranchAccessService} only exposes a principal-based {@code resolve}, so this module (like
     * those) resolves via its own already-injected {@code AppUserRepository} instead. */
    private AppUser resolveRequester(UUID actorUserId) {
        return actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
    }

    /** At most one active tax may be the default PER SCOPE - clear whichever one currently holds
     * that flag in this scope (branchId null = the global scope) before granting it elsewhere. */
    private void clearExistingDefaultTax(UUID branchId) {
        Optional<Tax> existing = branchId == null
                ? taxRepository.findByActiveTrueAndDefaultRateTrueAndBranchIsNull()
                : taxRepository.findByActiveTrueAndDefaultRateTrueAndBranchId(branchId);
        existing.ifPresent(t -> {
            t.setDefaultRate(false);
            taxRepository.save(t);
        });
    }

    // ---- Discount presets (RESTAURANT_MANAGE) ----
    //
    // Bistrodesk Phase 3: Discount.branch is optional - null (every pre-existing preset) is shared/
    // usable at every branch; set restricts it to one branch. Unlike Tax there's no code-based
    // override resolution here (a discount is picked by id, not a shared key), so this is pure
    // visibility/eligibility scoping - see applyDiscount's branch-match check below.

    @Transactional(readOnly = true)
    public List<Discount> listDiscounts(Set<UUID> accessibleBranchIds) {
        return discountRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(d -> visible(d.getBranch(), accessibleBranchIds))
                .toList();
    }

    @Transactional
    public Discount createDiscount(String name, DiscountType type, BigDecimal value, BigDecimal maxDiscountAmount,
                                    UUID applicableCategoryId, UUID branchId, UUID actorUserId) {
        MenuCategory category = applicableCategoryId == null ? null
                : menuCategoryRepository.findById(applicableCategoryId)
                        .orElseThrow(() -> ApiException.notFound("Menu category not found"));
        Branch branch = branchId == null ? null : branchRepository.findById(branchId)
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        if (branch != null) {
            branchAccessService.assertAccess(resolveRequester(actorUserId), branch.getId());
        }
        return discountRepository.save(Discount.builder().name(name).type(type).value(value)
                .maxDiscountAmount(maxDiscountAmount).applicableCategory(category).branch(branch).build());
    }

    @Transactional
    public Discount updateDiscount(UUID id, String name, BigDecimal value, BigDecimal maxDiscountAmount,
                                    UUID applicableCategoryId, boolean clearCategory, Boolean active,
                                    UUID branchId, boolean clearBranch, long version, UUID actorUserId) {
        Discount discount = discountRepository.findById(id).orElseThrow(() -> ApiException.notFound("Discount not found"));
        AppUser requester = resolveRequester(actorUserId);
        branchAccessService.assertAccess(requester, discount.getBranch() == null ? null : discount.getBranch().getId());
        if (discount.getVersion() != version) {
            throw new ObjectOptimisticLockingFailureException(Discount.class, id);
        }
        if (name != null) {
            discount.setName(name);
        }
        if (value != null) {
            discount.setValue(value);
        }
        if (maxDiscountAmount != null) {
            discount.setMaxDiscountAmount(maxDiscountAmount);
        }
        if (clearCategory) {
            discount.setApplicableCategory(null);
        } else if (applicableCategoryId != null) {
            discount.setApplicableCategory(menuCategoryRepository.findById(applicableCategoryId)
                    .orElseThrow(() -> ApiException.notFound("Menu category not found")));
        }
        if (clearBranch) {
            discount.setBranch(null);
        } else if (branchId != null) {
            branchAccessService.assertAccess(requester, branchId);
            discount.setBranch(branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found")));
        }
        if (active != null) {
            discount.setActive(active);
        }
        return discountRepository.save(discount);
    }

    // ---- Bill / discount application / payments ----

    @Transactional(readOnly = true)
    public BillingDtos.BillDto getBill(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        return toBillDto(order);
    }

    /** discountId picks a preset; otherwise typeRaw+value is a one-off manual discount. Either way requires DISCOUNT_APPROVE at the controller. */
    @Transactional
    public BillingDtos.BillDto applyDiscount(UUID orderId, UUID discountId, String typeRaw, BigDecimal value,
                                              String reason, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion);
        if (!DISCOUNT_EDITABLE_STATUSES.contains(order.getStatus())) {
            throw ApiException.conflict("ORDER_NOT_DISCOUNTABLE", "Order " + order.getOrderNumber()
                    + " must be Served or Bill Requested to change its discount (currently " + order.getStatus() + ").");
        }

        DiscountType type;
        BigDecimal amountOrPercent;
        String appliedName;
        BigDecimal maxDiscountAmount = null;
        MenuCategory applicableCategory = null;
        if (discountId != null) {
            Discount preset = discountRepository.findById(discountId).orElseThrow(() -> ApiException.notFound("Discount not found"));
            // Bistrodesk Phase 3 (requirement #6): a branch-restricted preset only applies to a bill
            // at that same branch - a Branch-A-only promotion must never discount a Branch-B order,
            // and (since there'd be no way to confirm the branches actually match) never a
            // branch-less order either. A shared preset (null branch) is always allowed, on any order.
            Branch presetBranch = preset.getBranch();
            Branch orderBranch = order.getEffectiveBranch();
            if (presetBranch != null && (orderBranch == null || !presetBranch.getId().equals(orderBranch.getId()))) {
                throw ApiException.badRequest("BRANCH_MISMATCH", "Discount '" + preset.getName()
                        + "' is only usable at " + presetBranch.getName() + ", not this order's branch.");
            }
            type = preset.getType();
            amountOrPercent = preset.getValue();
            appliedName = preset.getName();
            maxDiscountAmount = preset.getMaxDiscountAmount();
            applicableCategory = preset.getApplicableCategory();
        } else {
            if (typeRaw == null || value == null) {
                throw ApiException.badRequest("DISCOUNT_INPUT_REQUIRED", "Provide either a discountId or both type and value.");
            }
            type = parseDiscountType(typeRaw);
            amountOrPercent = value;
            appliedName = "Manual " + type.name();
        }

        // Round 12: a category-scoped preset only discounts the slice of the subtotal that belongs
        // to that category (e.g. a "Beverages 10% Off" preset never waives anything on the food
        // items on the same bill) - everything else about the calculation (percent-of-base vs flat
        // amount, then capped) works the same against this narrower base as it always has against
        // the whole subtotal. Note: the resulting single discountAmount still gets apportioned
        // across tax brackets proportionally by each bracket's overall subtotal share in
        // computeTaxLines() (an existing simplification, not new to this change) rather than only
        // relieving tax on the specific category's bracket - a finer-grained per-category tax split
        // would need computeTaxLines() itself restructured, out of scope for this fix.
        // applicableCategory is reassigned above (once, either from the preset or left null) - a
        // final copy is needed here since a lambda below captures it, and only effectively-final
        // locals may be captured.
        MenuCategory categoryForDiscount = applicableCategory;
        BigDecimal discountableBase = categoryForDiscount == null
                ? order.getSubtotal()
                : order.getItems().stream()
                        .filter(item -> item.getStatus() != OrderItemStatus.CANCELLED && item.getStatus() != OrderItemStatus.VOIDED)
                        .filter(item -> item.getMenuItem().getCategory() != null
                                && item.getMenuItem().getCategory().getId().equals(categoryForDiscount.getId()))
                        .map(OrderItem::lineTotal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discountAmount = type == DiscountType.PERCENTAGE
                ? discountableBase.multiply(amountOrPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : amountOrPercent;
        // Never let a discount exceed the base it's discounting (the whole bill, or just its
        // category slice) - a fixed-amount preset or manual entry bigger than that base would
        // otherwise flip totalAmount negative or waive more than the category itself is worth.
        discountAmount = discountAmount.min(discountableBase);
        // Round 12: an explicit per-preset ceiling (e.g. "up to ₹200 off") on top of the above -
        // applies regardless of type, since a PERCENTAGE preset on a big order can otherwise exceed
        // whatever cap the preset was actually designed around.
        if (maxDiscountAmount != null) {
            discountAmount = discountAmount.min(maxDiscountAmount);
        }

        order.setDiscountAmount(discountAmount);
        order.setDiscountReason(reason != null ? appliedName + " - " + reason : appliedName);
        order.recalculateTotals();
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "Order", saved.getId(), "DISCOUNT_APPLIED", null,
                discountAmount.toPlainString(), reason, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());
        return toBillDto(saved);
    }

    /** Freezes tax + service charge (computed on the post-discount subtotal) and moves BILL_REQUESTED -> BILLED.
     *
     * <p>POS patch (tip on the entire order): {@code tipAmount} is an optional, order-level tip
     * collected on the "Generate the final bill?" screen, i.e. before this freeze - when supplied
     * it is written onto the order BEFORE {@code recalculateTotals()} runs below, so the very same
     * bill this call returns (and every screen shown after it - payment, receipt) already carries
     * the tip inside {@code totalAmount} with nothing left to recompute. A {@code null} tipAmount
     * (the normal case for every existing caller, including the JavaFX desktop client, which never
     * sends this param) leaves {@code Order.tipAmount} exactly as it already was - fully
     * backward-compatible. This does not replace {@link #recordPayment}'s own, separate
     * {@code tipAmount} parameter (a tip added at the payment step, e.g. a card-terminal tip
     * captured after the bill is already generated) - that still adds on top of whatever is set
     * here, it is never overwritten by it. */
    @Transactional
    public BillingDtos.BillDto generateBill(UUID orderId, long expectedVersion, BigDecimal tipAmount, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion);
        if (!order.getStatus().canTransitionTo(OrderStatus.BILLED)) {
            throw ApiException.conflict("INVALID_ORDER_TRANSITION", "Order " + order.getOrderNumber()
                    + " cannot be billed from status " + order.getStatus() + " (must be Bill Requested first).");
        }
        if (tipAmount != null) {
            if (tipAmount.compareTo(BigDecimal.ZERO) < 0) {
                throw ApiException.badRequest("INVALID_TIP", "Tip amount cannot be negative.");
            }
            order.setTipAmount(tipAmount);
        }

        BigDecimal taxableBase = order.getSubtotal().subtract(order.getDiscountAmount());
        List<BillingDtos.TaxLineDto> taxLines = taxCalculationService.computeTaxLines(order, taxableBase);
        BigDecimal taxAmount = taxLines.stream().map(BillingDtos.TaxLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Restaurant restaurant = currentRestaurant();
        BigDecimal serviceChargeAmount = taxableBase.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : taxableBase.multiply(restaurant.getServiceChargePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        order.setTaxAmount(taxAmount);
        order.setServiceChargeAmount(serviceChargeAmount);
        order.recalculateTotals();
        order.setStatus(OrderStatus.BILLED);
        order.setBilledAt(LocalDateTime.now());
        syncTableStatus(order);
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "Order", saved.getId(), "ORDER_BILLED", null,
                saved.getTotalAmount().toPlainString(), null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_STATUS_CHANGED", saved.getId(), saved.getVersion(),
                Map.of("from", "BILL_REQUESTED", "to", "BILLED"));
        return toBillDto(saved);
    }

    /** Round 12 §11 - "Back" one step in the pre-payment billing workflow: BILLED (or the
     * now-internal-only BILL_REQUESTED, kept for backward compatibility now that §8 removes the
     * manual "Request Bill" click from the UI) back to SERVED, so a cashier who generated the bill
     * too early can reopen the discount and re-run Generate Bill instead of being stuck.
     * Deliberately NOT modeled as a forward transition in {@code OrderStatus#canTransitionTo} (that
     * graph stays forward-only for every other caller) - eligibility here is a narrow, explicit
     * business check instead: only ever allowed when not one payment (voided or otherwise) has ever
     * been recorded against this order, so there is zero risk of ever duplicating or orphaning a
     * payment (requirement's "never duplicates bills/payments"). Discount amount/reason are left
     * completely untouched ("preserves entered data") - only the frozen tax/service-charge numbers
     * are cleared, matching the zero-value invariant every not-yet-billed SERVED order already has
     * (see {@link #toBillDto}'s live-recompute branch for {@code DISCOUNT_EDITABLE_STATUSES}). */
    @Transactional
    public BillingDtos.BillDto backToServed(UUID orderId, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion);
        OrderStatus from = order.getStatus();
        if (from != OrderStatus.BILLED && from != OrderStatus.BILL_REQUESTED) {
            throw ApiException.conflict("ORDER_NOT_REVERSIBLE", "Order " + order.getOrderNumber()
                    + " can only go back to Served from Billed (currently " + from + ").");
        }
        List<Payment> payments = paymentRepository.findByOrderIdOrderByReceivedAtAsc(order.getId());
        if (!payments.isEmpty()) {
            throw ApiException.conflict("ORDER_HAS_PAYMENTS", "Order " + order.getOrderNumber()
                    + " already has a payment recorded and can no longer go back - void the payment instead if it was entered in error.");
        }
        order.setTaxAmount(BigDecimal.ZERO);
        order.setServiceChargeAmount(BigDecimal.ZERO);
        order.setBilledAt(null);
        order.setStatus(OrderStatus.SERVED);
        order.recalculateTotals();
        syncTableStatus(order);
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "Order", saved.getId(), "ORDER_BILL_REVERTED", from.name(),
                OrderStatus.SERVED.name(), null, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_STATUS_CHANGED", saved.getId(), saved.getVersion(),
                Map.of("from", from.name(), "to", "SERVED"));
        return toBillDto(saved);
    }

    /**
     * Records one tender against the bill; an order may receive several calls to this (split
     * bill - part cash, part card, or several guests each covering a share) until the balance
     * reaches zero. CASH supplies {@code tenderedAmount} (what the guest handed over) and the
     * service works out how much of that actually applies vs. change owed; every other method
     * supplies an exact {@code amount} instead.
     */
    @Transactional
    public BillingDtos.BillDto recordPayment(UUID orderId, String methodRaw, BigDecimal amount, BigDecimal tenderedAmount,
                                              String referenceNumber, BigDecimal tipAmount, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion);
        if (!PAYABLE_STATUSES.contains(order.getStatus())) {
            throw ApiException.conflict("ORDER_NOT_BILLED", "Order " + order.getOrderNumber()
                    + " must be billed before recording a payment (currently " + order.getStatus() + ").");
        }
        PaymentMethod method = parsePaymentMethod(methodRaw);
        AppUser cashier = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        if (cashier == null) {
            throw ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required to record a payment.");
        }
        // Bistrodesk Phase 5 (confirmed bug, found while wiring branch-scoped reporting): Order#cashier
        // was declared and read by ExcessiveDiscountRule/PeerBaselineOutlierRule (both keyed on "the
        // user who billed/closed the order") but never once WRITTEN anywhere in this codebase - so both
        // fraud rules silently never fired (their per-cashier gross/discount maps were always empty).
        // Fixed at the one place that actually knows who is closing out the order's balance; on a split
        // bill this naturally ends up attributed to whoever recorded the LAST (balance-clearing) tender,
        // which is the same "who closed it" reading those rules' own javadoc already documents.
        order.setCashier(cashier);

        // A card terminal typically captures the tip at swipe time - folding it into the total
        // here (before computing balance due) means the same payment call that captures the tip
        // also collects it, rather than needing a separate round trip.
        if (tipAmount != null && tipAmount.compareTo(BigDecimal.ZERO) > 0) {
            order.setTipAmount(order.getTipAmount().add(tipAmount));
            order.recalculateTotals();
        }

        BigDecimal balanceDue = balanceDue(order);
        if (balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.conflict("ORDER_ALREADY_PAID", "Order " + order.getOrderNumber() + " has no remaining balance.");
        }

        BigDecimal appliedAmount;
        BigDecimal changeAmount = null;
        BigDecimal tendered = null;
        if (method == PaymentMethod.CASH) {
            if (tenderedAmount == null || tenderedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("TENDERED_AMOUNT_REQUIRED", "tenderedAmount is required for a CASH payment.");
            }
            tendered = tenderedAmount;
            appliedAmount = tenderedAmount.min(balanceDue);
            changeAmount = tenderedAmount.subtract(appliedAmount);
        } else {
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("AMOUNT_REQUIRED", "amount is required for a " + method + " payment.");
            }
            if (amount.compareTo(balanceDue) > 0) {
                throw ApiException.badRequest("AMOUNT_EXCEEDS_BALANCE", "Payment amount exceeds the remaining balance of " + balanceDue + ".");
            }
            appliedAmount = amount;
        }

        Payment payment = Payment.builder()
                .order(order)
                .method(method)
                .amount(appliedAmount)
                .tenderedAmount(tendered)
                .changeAmount(changeAmount)
                .referenceNumber(referenceNumber)
                .receiptNumber(numberGeneratorService.next("RCPT"))
                .receivedBy(cashier)
                .receivedAt(LocalDateTime.now())
                .build();
        paymentRepository.save(payment);

        BigDecimal newBalance = balanceDue.subtract(appliedAmount);
        order.setPaymentStatus(newBalance.compareTo(BigDecimal.ZERO) <= 0 ? PaymentStatus.PAID : PaymentStatus.PARTIALLY_PAID);

        if (order.getStatus() == OrderStatus.BILLED) {
            order.setStatus(OrderStatus.PAYMENT_PENDING);
        }
        if (newBalance.compareTo(BigDecimal.ZERO) <= 0 && order.getStatus().canTransitionTo(OrderStatus.PAID)) {
            order.setStatus(OrderStatus.PAID);
            order.setPaidAt(LocalDateTime.now());
        }
        syncTableStatus(order);
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "Payment", payment.getId(), "PAYMENT_RECORDED", null,
                appliedAmount.toPlainString(), method.name(), CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_PAYMENT_RECORDED", saved.getId(), saved.getVersion(),
                Map.of("method", method.name(), "amount", appliedAmount));

        // Round 13 (AI Backbone Addendum F1.5): POST_PRINT_VOID only ever fires on a CASH tender -
        // see that rule's javadoc. Wrapped defensively even though FraudRuleEngineService already
        // isolates failures per-rule internally - recording a payment must never fail because of a
        // problem in this new, additive side-effect.
        if (method == PaymentMethod.CASH) {
            try {
                fraudRuleEngineService.evaluateEvent(new FraudRuleContext(FraudRuleEventType.CASH_PAYMENT_RECORDED,
                        LocalDate.now(), saved, payment, null, null, actorUserId, null));
            } catch (Exception ex) {
                log.error("Fraud rule engine failed evaluating payment {} - continuing without it.", payment.getId(), ex);
            }
        }
        return toBillDto(saved);
    }

    /** Corrects a mis-entered payment (soft-delete via {@code voided}) - only while the order isn't fully paid yet; see class javadoc. */
    @Transactional
    public BillingDtos.BillDto voidPayment(UUID orderId, UUID paymentId, String reason, long expectedVersion, UUID actorUserId) {
        Order order = loadForUpdate(orderId, expectedVersion);
        if (order.getStatus() != OrderStatus.BILLED && order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw ApiException.conflict("ORDER_ALREADY_SETTLED",
                    "Payments can only be voided before the order is fully paid (currently " + order.getStatus() + ").");
        }
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (!payment.getOrder().getId().equals(orderId)) {
            throw ApiException.badRequest("PAYMENT_ORDER_MISMATCH", "That payment does not belong to this order.");
        }
        if (payment.isVoided()) {
            throw ApiException.conflict("PAYMENT_ALREADY_VOIDED", "This payment was already voided.");
        }
        payment.setVoided(true);
        payment.setVoidReason(reason);
        paymentRepository.save(payment);

        BigDecimal remainingPaid = balanceAfterVoiding(order.getId());
        order.setPaymentStatus(remainingPaid.compareTo(BigDecimal.ZERO) <= 0 ? PaymentStatus.UNPAID : PaymentStatus.PARTIALLY_PAID);
        Order saved = orderRepository.save(order);

        auditService.record(actorUserId, null, "Payment", paymentId, "PAYMENT_VOIDED", null, null, reason, CorrelationIdHolder.get());
        eventPublisher.publish("/topic/orders", "ORDER_UPDATED", saved.getId(), saved.getVersion(), Map.of());
        return toBillDto(saved);
    }

    @Transactional(readOnly = true)
    public BillingDtos.SplitBillResponse splitBillEvenly(UUID orderId, int ways) {
        if (ways < 2) {
            throw ApiException.badRequest("INVALID_SPLIT", "Split must be into at least 2 ways.");
        }
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        BigDecimal total = order.getTotalAmount();
        BigDecimal baseShare = total.divide(BigDecimal.valueOf(ways), 2, RoundingMode.DOWN);
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal runningTotal = BigDecimal.ZERO;
        for (int i = 0; i < ways - 1; i++) {
            shares.add(baseShare);
            runningTotal = runningTotal.add(baseShare);
        }
        // Last share absorbs the rounding remainder so shares always sum to exactly totalAmount -
        // never leave a paisa/cent unaccounted for across N equal-ish shares.
        shares.add(total.subtract(runningTotal));
        return new BillingDtos.SplitBillResponse(order.getId(), ways, total, shares);
    }

    /**
     * Formats a plain-text receipt from the current bill. Tax line-by-line breakdown is
     * recomputed live from the current menu/tax config rather than persisted at bill time - the
     * frozen, authoritative number is {@code order.getTaxAmount()} (used for the "TOTAL" line and
     * everywhere money actually matters); the breakdown is display-only and could only diverge
     * from history if a tax rate were edited after this specific order was billed, which is rare
     * enough to accept for a first Phase 4 pass rather than adding a persisted tax-line entity now.
     */
    @Transactional(readOnly = true)
    public BillingDtos.ReceiptDto generateReceiptText(UUID orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        Restaurant restaurant = currentRestaurant();
        BillingDtos.BillDto bill = toBillDto(order);
        String currency = restaurant.getCurrencySymbol();
        // Cosmetic divider/heading width only (58mm vs 80mm paper) - restaurant.getReceiptPaperWidthChars()
        // defaults to 40, matching this method's original hardcoded value. Deliberately does NOT
        // reflow line()'s fixed-width money columns below - see Restaurant.receiptPaperWidthChars's
        // javadoc for why that stays untouched.
        int width = restaurant.getReceiptPaperWidthChars() > 0 ? restaurant.getReceiptPaperWidthChars() : 40;

        // Bistrodesk branch-isolation release (requirement #4): GSTIN/support phone/footer are now
        // this order's own branch's profile fields, not the install-wide Restaurant singleton's -
        // see Branch's javadoc. Falls back to the Restaurant row only for a legacy order with no
        // resolvable branch (order.getEffectiveBranch() == null - the same null-tolerant convention
        // every other branch check in this codebase uses), so an old, pre-migration order still
        // prints something sensible rather than a blank header. currencySymbol/receiptPaperWidthChars
        // above stay reading Restaurant - genuinely install-wide, out of scope for this release.
        Branch branch = order.getEffectiveBranch();
        String gstin = branch != null ? branch.getGstin() : restaurant.getGstin();
        String supportPhone = branch != null ? branch.getSupportPhone() : restaurant.getSupportPhone();
        String receiptFooterText = branch != null ? branch.getReceiptFooterText() : restaurant.getReceiptFooterText();

        StringBuilder sb = new StringBuilder();
        sb.append(center(restaurant.getName(), width)).append('\n');
        if (gstin != null) {
            sb.append("GSTIN: ").append(gstin).append('\n');
        }
        if (supportPhone != null) {
            sb.append("Ph: ").append(supportPhone).append('\n');
        }
        sb.append("-".repeat(width)).append('\n');
        sb.append("Order: ").append(order.getOrderNumber());
        if (order.getTable() != null) {
            sb.append("   Table: ").append(order.getTable().getName());
        }
        sb.append('\n');
        // Bistrodesk Phase 7 (requirement #2): this receipt printed nothing about the customer at
        // all before this - a real gap once a name/phone (or a full directory record, via the newer
        // Order.customer FK) is actually captured on the order. customerName/customerPhone are kept
        // as the single source read here regardless of whether a directory record is linked,
        // because attaching one (OrderService#updateCustomerDetails) always syncs these same two
        // fields from it - so every other reader of "the customer on this order" (receipts, order
        // history lists, etc.) keeps working unchanged without needing to become FK-aware itself.
        if (order.getCustomerName() != null || order.getCustomerPhone() != null) {
            if (order.getCustomerName() != null) {
                sb.append("Customer: ").append(order.getCustomerName());
                if (order.getCustomerPhone() != null) {
                    sb.append(" (").append(order.getCustomerPhone()).append(')');
                }
                sb.append('\n');
            } else {
                sb.append("Customer Ph: ").append(order.getCustomerPhone()).append('\n');
            }
        }
        sb.append("-".repeat(width)).append('\n');

        for (OrderItem item : order.getItems()) {
            if (item.getStatus() == OrderItemStatus.CANCELLED || item.getStatus() == OrderItemStatus.VOIDED) {
                continue;
            }
            sb.append(line(item.getMenuItem().getName() + " x" + item.getQuantity().stripTrailingZeros().toPlainString(),
                    currency, item.lineTotal()));
        }
        sb.append("-".repeat(width)).append('\n');
        sb.append(line("Subtotal", currency, bill.subtotal()));
        if (bill.discountAmount().compareTo(BigDecimal.ZERO) > 0) {
            String label = "Discount" + (order.getDiscountReason() != null ? " (" + order.getDiscountReason() + ")" : "");
            sb.append(line(label, currency, bill.discountAmount().negate()));
        }
        for (BillingDtos.TaxLineDto taxLine : bill.taxLines()) {
            sb.append(line(taxLine.name() + " (" + taxLine.ratePercent() + "%)", currency, taxLine.amount()));
        }
        if (bill.serviceChargeAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(line("Service Charge", currency, bill.serviceChargeAmount()));
        }
        if (bill.tipAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(line("Tip", currency, bill.tipAmount()));
        }
        sb.append("-".repeat(width)).append('\n');
        sb.append(line("TOTAL", currency, bill.totalAmount()));
        sb.append("-".repeat(width)).append('\n');
        for (BillingDtos.PaymentDto payment : bill.payments()) {
            if (payment.voided()) {
                continue;
            }
            sb.append(line(payment.method() + " (" + payment.receiptNumber() + ")", currency, payment.amount()));
        }
        if (bill.balanceDue().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(line("Balance Due", currency, bill.balanceDue()));
        }
        sb.append("-".repeat(width)).append('\n');
        String footer = receiptFooterText;
        sb.append(center((footer == null || footer.isBlank()) ? "Thank you, visit again!" : footer, width)).append('\n');

        return new BillingDtos.ReceiptDto(order.getOrderNumber(), sb.toString());
    }

    // ---- Cash management ----

    @Transactional
    public CashMovement recordCashMovement(String typeRaw, BigDecimal amount, String reason, UUID actorUserId) {
        CashMovementType type = parseCashMovementType(typeRaw);
        AppUser user = actorUserId == null ? null : appUserRepository.findById(actorUserId).orElse(null);
        if (user == null) {
            throw ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required to record a cash movement.");
        }
        CashMovement saved = cashMovementRepository.save(CashMovement.builder().type(type).amount(amount).reason(reason).recordedBy(user).build());
        auditService.record(actorUserId, null, "CashMovement", saved.getId(), type.name(), null, amount.toPlainString(), reason, CorrelationIdHolder.get());
        return saved;
    }

    /**
     * Round 13 (AI Backbone Addendum F1.5): logs a cash-drawer open that isn't attached to any
     * sale - "No Sale" is a standard POS action (verifying change, correcting a mis-ring) but was
     * never tracked as a discrete event before this round, so NO_SALE_FREQUENCY (see {@code
     * NoSaleFrequencyRule}) had nothing to count. This method's only job is to write that audit
     * trail entry and let the fraud engine look at the resulting frequency - it has no side effect
     * on any order/payment/inventory state.
     */
    @Transactional
    public void recordNoSale(String reason, UUID actorUserId, UUID deviceId) {
        if (actorUserId == null) {
            throw ApiException.badRequest("ACTOR_REQUIRED", "A logged-in user is required to record a no-sale drawer open.");
        }
        auditService.record(actorUserId, deviceId, "CashDrawer", null, NoSaleFrequencyRule.NO_SALE_AUDIT_ACTION, null, null,
                reason, CorrelationIdHolder.get());
        try {
            fraudRuleEngineService.evaluateEvent(new FraudRuleContext(FraudRuleEventType.NO_SALE_DRAWER_OPEN,
                    LocalDate.now(), null, null, null, null, actorUserId, deviceId));
        } catch (Exception ex) {
            log.error("Fraud rule engine failed evaluating a no-sale event for user {} - continuing without it.", actorUserId, ex);
        }
    }

    @Transactional(readOnly = true)
    public BillingDtos.CashSummaryDto getCashSummary(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);

        BigDecimal totalCashPayments = paymentRepository.findByMethodAndVoidedFalseAndReceivedAtBetween(PaymentMethod.CASH, start, end)
                .stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CashMovement> movements = cashMovementRepository.findByCreatedAtBetween(start, end);
        BigDecimal totalCashIn = movements.stream().filter(m -> m.getType() == CashMovementType.CASH_IN)
                .map(CashMovement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCashOut = movements.stream().filter(m -> m.getType() == CashMovementType.CASH_OUT)
                .map(CashMovement::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expected = totalCashPayments.add(totalCashIn).subtract(totalCashOut);
        return new BillingDtos.CashSummaryDto(date, totalCashPayments, totalCashIn, totalCashOut, expected);
    }

    /** Raw list of cash-in/cash-out entries for a single day, newest first - backs the Cash
     * Management screen's ledger view (the cash-summary endpoint above only returns aggregated
     * totals, which isn't enough to show/expense-audit individual Expense/Withdrawal/Top-Up
     * entries). Reuses the same day-window and repository method as getCashSummary. */
    @Transactional(readOnly = true)
    public List<BillingDtos.CashMovementDto> listCashMovements(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return cashMovementRepository.findByCreatedAtBetween(start, end).stream()
                .sorted(Comparator.comparing(CashMovement::getCreatedAt).reversed())
                .map(m -> new BillingDtos.CashMovementDto(m.getId(), m.getType().name(), m.getAmount(), m.getReason(),
                        m.getRecordedBy() == null ? null : m.getRecordedBy().getDisplayName(), m.getCreatedAt()))
                .toList();
    }

    // ---- helpers ----

    private BigDecimal balanceDue(Order order) {
        BigDecimal paid = paymentRepository.findByOrderIdOrderByReceivedAtAsc(order.getId()).stream()
                .filter(p -> !p.isVoided())
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return order.getTotalAmount().subtract(paid).max(BigDecimal.ZERO);
    }

    private BigDecimal balanceAfterVoiding(UUID orderId) {
        return paymentRepository.findByOrderIdOrderByReceivedAtAsc(orderId).stream()
                .filter(p -> !p.isVoided())
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Builds the DTO the client renders as "the bill". {@code taxLines} is always recomputed live
     * from the current tax config (see this method's other javadoc note above {@link #generateBill}
     * about that being intentional for the receipt breakdown) - but before Phase 4's freeze point
     * ({@link #generateBill}, SERVED/BILL_REQUESTED), {@code order.getTaxAmount()},
     * {@code order.getServiceChargeAmount()} and {@code order.getTotalAmount()} are still whatever
     * they were left at (zero, unless a previous bill on this same order somehow set them), because
     * nothing populates them until generateBill() actually runs and freezes them.
     *
     * <p>Rendering the live {@code taxLines} breakdown (e.g. "GST (5%) ₹12.50") next to a TOTAL that
     * doesn't yet include that amount reads as exactly the bug this was reported as - "tax is
     * calculating properly but not adding to the bill". The fix: while the order is still in a
     * discount-editable (pre-bill) status, preview {@code taxAmount}/{@code serviceChargeAmount}/
     * {@code totalAmount} using the exact same formula {@link #generateBill} will freeze them with,
     * so what's on screen always foots. This is display-only - the persisted order is untouched
     * here; generateBill() remains the only place that actually freezes these fields.
     */
    private BillingDtos.BillDto toBillDto(Order order) {
        BigDecimal taxableBase = order.getSubtotal().subtract(order.getDiscountAmount());
        List<BillingDtos.TaxLineDto> taxLines = taxCalculationService.computeTaxLines(order, taxableBase);
        List<Payment> payments = paymentRepository.findByOrderIdOrderByReceivedAtAsc(order.getId());
        BigDecimal amountPaid = payments.stream().filter(p -> !p.isVoided()).map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal taxAmount;
        BigDecimal serviceChargeAmount;
        BigDecimal totalAmount;
        if (DISCOUNT_EDITABLE_STATUSES.contains(order.getStatus())) {
            taxAmount = taxLines.stream().map(BillingDtos.TaxLineDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            Restaurant restaurant = currentRestaurant();
            serviceChargeAmount = taxableBase.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : taxableBase.multiply(restaurant.getServiceChargePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            totalAmount = taxableBase.add(taxAmount).add(serviceChargeAmount).add(order.getTipAmount());
        } else {
            taxAmount = order.getTaxAmount();
            serviceChargeAmount = order.getServiceChargeAmount();
            totalAmount = order.getTotalAmount();
        }

        BigDecimal balanceDue = totalAmount.subtract(amountPaid).max(BigDecimal.ZERO);
        List<BillingDtos.PaymentDto> paymentDtos = payments.stream().map(this::toPaymentDto).toList();

        return new BillingDtos.BillDto(order.getId(), order.getOrderNumber(), order.getStatus().name(),
                order.getPaymentStatus().name(), order.getSubtotal(), order.getDiscountAmount(), order.getDiscountReason(),
                taxLines, taxAmount, serviceChargeAmount, order.getTipAmount(),
                totalAmount, amountPaid, balanceDue, paymentDtos, order.getVersion());
    }

    private BillingDtos.PaymentDto toPaymentDto(Payment p) {
        return new BillingDtos.PaymentDto(p.getId(), p.getMethod().name(), p.getAmount(), p.getTenderedAmount(),
                p.getChangeAmount(), p.getReferenceNumber(), p.getReceiptNumber(),
                p.getReceivedBy() == null ? null : p.getReceivedBy().getDisplayName(), p.getReceivedAt(),
                p.isVoided(), p.getVoidReason(), p.getVersion());
    }

    private Order loadForUpdate(UUID orderId, long expectedVersion) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (order.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(Order.class, orderId);
        }
        return order;
    }

    private Restaurant currentRestaurant() {
        return restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
    }

    /**
     * Mirrors {@code OrderService#syncTableStatus} for the two status changes this service makes
     * directly (BILLED in {@link #generateBill}, PAYMENT_PENDING/PAID in {@link #recordPayment})
     * rather than through {@code OrderService.updateOrderStatus}, which is the only other place
     * that keeps the table matrix in lock-step with order status. Without this, a fully paid
     * order's table would stay stuck on whatever color it was at BILL_REQUESTED/BILLED forever,
     * since nothing else ever tells the table it's free again.
     *
     * <p>This alone used to not be enough: {@code RestaurantTable#canTransitionTo} deliberately
     * forbade jumping straight from an occupied-ish status (BILL_REQUESTED, PAYMENT_PENDING, ...)
     * to AVAILABLE, on the theory that only CLOSED/BLOCKED should free a table - but nothing in
     * this codebase's order lifecycle ever actually sets ORDER status to CLOSED after PAID, so
     * that rule silently blocked every "guest just paid" table-free-up. Fixed at the source in
     * {@code RestaurantTable#canTransitionTo} rather than worked around here.
     */
    private void syncTableStatus(Order order) {
        if (order.getTable() == null) {
            return;
        }
        TableStatus mapped = switch (order.getStatus()) {
            case BILLED, PAYMENT_PENDING -> TableStatus.PAYMENT_PENDING;
            case PAID, CLOSED, CANCELLED -> TableStatus.AVAILABLE;
            // Round 12 §11 - the only other status this service ever sets directly, via
            // #backToServed's undo of a too-early Generate Bill; mirrors OrderService's own
            // SERVED -> OCCUPIED mapping so the table matrix agrees with the order either way.
            case SERVED -> TableStatus.OCCUPIED;
            default -> null; // not a status this service ever sets - leave the table alone
        };
        if (mapped == null) {
            return;
        }
        RestaurantTable table = order.getTable();
        if (table.getStatus() != mapped && table.canTransitionTo(mapped)) {
            table.setStatus(mapped);
            tableRepository.save(table);
            eventPublisher.publish("/topic/tables", "TABLE_STATUS_CHANGED", table.getId(), table.getVersion(),
                    Map.of("tableName", table.getName(), "status", mapped.name()));
        }
    }

    private String line(String label, String currency, BigDecimal amount) {
        String trimmed = label.length() <= 26 ? label : label.substring(0, 25) + "…";
        return String.format("%-26s %11s%n", trimmed, currency + amount.setScale(2, RoundingMode.HALF_UP));
    }

    private String center(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat((width - text.length()) / 2) + text;
    }

    private DiscountType parseDiscountType(String raw) {
        try {
            return DiscountType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_DISCOUNT_TYPE", "Unknown discount type: " + raw);
        }
    }

    private PaymentMethod parsePaymentMethod(String raw) {
        try {
            return PaymentMethod.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_PAYMENT_METHOD", "Unknown payment method: " + raw);
        }
    }

    private CashMovementType parseCashMovementType(String raw) {
        try {
            return CashMovementType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_CASH_MOVEMENT_TYPE", "Unknown cash movement type: " + raw);
        }
    }
}
