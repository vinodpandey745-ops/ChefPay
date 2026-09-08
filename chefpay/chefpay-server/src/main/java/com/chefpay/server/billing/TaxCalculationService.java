package com.chefpay.server.billing;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Order;
import com.chefpay.core.domain.OrderItem;
import com.chefpay.core.domain.OrderItemStatus;
import com.chefpay.core.domain.Tax;
import com.chefpay.core.repository.TaxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bistrodesk Phase 6 (bug #12, "tax not live in cart"): this per-item tax-bracket computation used
 * to live only inside {@code BillingService}, only ever reached from the checkout-time bill
 * (BillingService#generateBill/#toBillDto) - so the plain {@code OrderDto} returned from every
 * pre-checkout cart mutation (add item / change quantity / remove item) never carried a live {@code
 * taxAmount} at all; {@code Order#recalculateTotals()} deliberately passes {@code taxAmount}
 * through unchanged (see its own javadoc) precisely because nothing else ever computed a fresh
 * value for it to use, so the running cart showed the tax line stuck at zero until the moment the
 * bill was actually generated. Extracted here, byte-for-byte the same logic that lived in
 * BillingService, so {@code OrderService} can call the exact same bracket math on every item
 * mutation and BillingService keeps using it for the real (and live-preview) bill - one
 * calculation, multiple callers, which is what keeps the running cart, the checkout preview and the
 * final frozen bill from ever being able to drift apart the way a second, hand-rolled frontend tax
 * formula risked (the reason a frontend-side recompute was rejected in favor of this).
 */
@Component
@RequiredArgsConstructor
public class TaxCalculationService {

    private final TaxRepository taxRepository;

    /**
     * Groups non-cancelled line totals by their effective tax rate (item's own {@code taxCode},
     * falling back to the restaurant's default tax), then applies {@code taxableBase}
     * proportionally across each bracket by its share of the order's own subtotal - a discounted
     * order shouldn't pay tax on the part of the price that was waived. Before any discount has
     * been applied (the common case while an order is still being built pre-SERVED), {@code
     * taxableBase} is simply the order's subtotal itself, since {@code discountAmount} is still
     * zero at that point.
     *
     * <p>Bistrodesk Phase 3 (requirement #6): both the per-item {@code taxCode} lookup ({@link
     * #resolveTax}) and the no-code default ({@link #resolveDefaultTax}) resolve against the
     * order's own branch (via {@link Order#getEffectiveBranch()}) - that branch's override takes
     * precedence over the global rate/default for the same code, exactly as configured on the Tax
     * entity itself.
     */
    public List<BillingDtos.TaxLineDto> computeTaxLines(Order order, BigDecimal taxableBase) {
        BigDecimal subtotal = order.getSubtotal();
        if (taxableBase.compareTo(BigDecimal.ZERO) <= 0 || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }

        Branch effectiveBranch = order.getEffectiveBranch();
        UUID branchId = effectiveBranch == null ? null : effectiveBranch.getId();
        Tax defaultTax = resolveDefaultTax(branchId);
        Map<UUID, BigDecimal> lineTotalByTaxId = new LinkedHashMap<>();
        Map<UUID, Tax> taxById = new LinkedHashMap<>();

        for (OrderItem item : order.getItems()) {
            if (item.getStatus() == OrderItemStatus.CANCELLED || item.getStatus() == OrderItemStatus.VOIDED) {
                continue;
            }
            Tax tax = resolveTax(item.getMenuItem().getTaxCode(), branchId, defaultTax);
            if (tax == null) {
                continue;
            }
            lineTotalByTaxId.merge(tax.getId(), item.lineTotal(), BigDecimal::add);
            taxById.putIfAbsent(tax.getId(), tax);
        }

        List<BillingDtos.TaxLineDto> lines = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : lineTotalByTaxId.entrySet()) {
            Tax tax = taxById.get(entry.getKey());
            BigDecimal taxableForBracket = entry.getValue().multiply(taxableBase).divide(subtotal, 2, RoundingMode.HALF_UP);
            BigDecimal amount = taxableForBracket.multiply(tax.getRatePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            lines.add(new BillingDtos.TaxLineDto(tax.getName(), tax.getRatePercent(), taxableForBracket, amount));
        }
        return lines;
    }

    /** Convenience for a caller (namely {@code OrderService}, on every pre-checkout item mutation)
     * that only needs the running total, not the itemized per-rate breakdown {@link
     * #computeTaxLines} returns for the receipt/bill screens. */
    public BigDecimal computeTaxAmount(Order order, BigDecimal taxableBase) {
        return computeTaxLines(order, taxableBase).stream()
                .map(BillingDtos.TaxLineDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Resolves the tax that applies when a line has no more specific {@code taxCode} match: this
     * branch's own default if one is configured, otherwise the global default. */
    private Tax resolveDefaultTax(UUID branchId) {
        if (branchId != null) {
            Optional<Tax> branchDefault = taxRepository.findByActiveTrueAndDefaultRateTrueAndBranchId(branchId);
            if (branchDefault.isPresent()) {
                return branchDefault.get();
            }
        }
        return taxRepository.findByActiveTrueAndDefaultRateTrueAndBranchIsNull().orElse(null);
    }

    /** The code's branch-specific override at {@code branchId} if one is configured, else the
     * code's global rate, else (no row at all for this code) {@code defaultTax}. */
    private Tax resolveTax(String taxCode, UUID branchId, Tax defaultTax) {
        if (taxCode == null) {
            return defaultTax;
        }
        if (branchId != null) {
            Optional<Tax> branchOverride = taxRepository.findByCodeAndBranchId(taxCode, branchId);
            if (branchOverride.isPresent()) {
                return branchOverride.filter(Tax::isActive).orElse(defaultTax);
            }
        }
        return taxRepository.findByCodeAndBranchIsNull(taxCode).filter(Tax::isActive).orElse(defaultTax);
    }
}
