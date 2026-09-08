package com.chefpay.server.billing;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.CashMovement;
import com.chefpay.core.domain.Discount;
import com.chefpay.core.domain.DiscountType;
import com.chefpay.core.domain.Tax;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tax config, discount presets, bill generation, payments (incl. split bill), receipts and cash
 * management - Phase 4 (ARCHITECTURE.md §5/§13). Item-level and order-lifecycle transitions stay
 * on {@code OrderController} (§8's "one owner per state machine"); this controller only adds what
 * is genuinely billing-specific.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final EmailReceiptService emailReceiptService;
    private final BranchAccessService branchAccessService;

    // ---- Tax config ----

    /** Omitted {@code branchId} lists every tax the caller can see (global + their own branch(es)'
     * overrides, per {@link BranchAccessService#accessibleBranchIds}); an explicit {@code branchId}
     * is access-checked and narrows to just that scope - same convention as {@code MenuController#list}. */
    @GetMapping("/taxes")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE') or hasAuthority('BILLING_MANAGE')")
    public ApiResponse<List<BillingDtos.TaxDto>> listTaxes(@RequestParam(required = false) UUID branchId,
                                                            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> accessible = resolveAccessibleBranchIds(branchId, principal);
        return ApiResponse.ok(billingService.listTaxes(accessible).stream().map(this::toTaxDto).toList());
    }

    /** Bistrodesk Phase 3 (requirement #6) "effective config" view - see {@code
     * BillingService#listEffectiveTaxes}'s javadoc. {@code branchId} is required (there is no
     * "effective" rate without a branch to resolve one for) and access-checked. */
    @GetMapping("/taxes/effective")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE') or hasAuthority('BILLING_MANAGE')")
    public ApiResponse<List<BillingDtos.TaxDto>> listEffectiveTaxes(@RequestParam UUID branchId,
                                                                     @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        branchAccessService.assertAccess(principal, branchId);
        return ApiResponse.ok(billingService.listEffectiveTaxes(branchId).stream().map(this::toTaxDto).toList());
    }

    @PostMapping("/taxes")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE')")
    public ApiResponse<BillingDtos.TaxDto> createTax(@Valid @RequestBody BillingDtos.CreateTaxRequest request,
                                                      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(toTaxDto(billingService.createTax(request.name(), request.code(), request.ratePercent(),
                request.defaultRate(), request.branchId(), userId(principal))));
    }

    @PatchMapping("/taxes/{id}")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE')")
    public ApiResponse<BillingDtos.TaxDto> updateTax(@PathVariable UUID id, @RequestBody BillingDtos.UpdateTaxRequest request,
                                                      @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(toTaxDto(billingService.updateTax(id, request.name(), request.ratePercent(),
                request.active(), request.defaultRate(), request.version(), userId(principal))));
    }

    // ---- Discount presets ----

    /** Same accessible-branch convention as {@link #listTaxes}. */
    @GetMapping("/discounts")
    @PreAuthorize("hasAuthority('DISCOUNT_APPROVE') or hasAuthority('BILLING_MANAGE') or hasAuthority('RESTAURANT_MANAGE')")
    public ApiResponse<List<BillingDtos.DiscountDto>> listDiscounts(@RequestParam(required = false) UUID branchId,
                                                                     @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Set<UUID> accessible = resolveAccessibleBranchIds(branchId, principal);
        return ApiResponse.ok(billingService.listDiscounts(accessible).stream().map(this::toDiscountDto).toList());
    }

    @PostMapping("/discounts")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE')")
    public ApiResponse<BillingDtos.DiscountDto> createDiscount(@Valid @RequestBody BillingDtos.CreateDiscountRequest request,
                                                                @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(toDiscountDto(billingService.createDiscount(request.name(), parseType(request.type()), request.value(),
                request.maxDiscountAmount(), request.applicableCategoryId(), request.branchId(), userId(principal))));
    }

    @PatchMapping("/discounts/{id}")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE')")
    public ApiResponse<BillingDtos.DiscountDto> updateDiscount(@PathVariable UUID id, @RequestBody BillingDtos.UpdateDiscountRequest request,
                                                                @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(toDiscountDto(billingService.updateDiscount(id, request.name(), request.value(),
                request.maxDiscountAmount(), request.applicableCategoryId(), request.clearCategory(), request.active(),
                request.branchId(), request.clearBranch(), request.version(), userId(principal))));
    }

    private Set<UUID> resolveAccessibleBranchIds(UUID requestedBranchId, AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        if (requestedBranchId != null) {
            branchAccessService.assertAccess(requester, requestedBranchId);
            return Set.of(requestedBranchId);
        }
        return branchAccessService.accessibleBranchIds(requester);
    }

    // ---- Bill / payments ----

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('BILLING_MANAGE') or hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<BillingDtos.BillDto> getBill(@PathVariable UUID orderId) {
        return ApiResponse.ok(billingService.getBill(orderId));
    }

    @PostMapping("/orders/{orderId}/discount")
    @PreAuthorize("hasAuthority('DISCOUNT_APPROVE')")
    public ApiResponse<BillingDtos.BillDto> applyDiscount(@PathVariable UUID orderId, @RequestBody BillingDtos.ApplyDiscountRequest request,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(billingService.applyDiscount(orderId, request.discountId(), request.type(), request.value(),
                request.reason(), request.orderVersion(), userId(principal)));
    }

    /** {@code tipAmount} is optional (POS patch - tip on the entire order, collected on the
     * "Generate the final bill?" screen so it's already included by the time this call returns);
     * omitting it (every existing caller, incl. the JavaFX desktop client) leaves the order's tip
     * exactly as it already was. See {@code BillingService#generateBill}'s javadoc. */
    @PostMapping("/orders/{orderId}/generate")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<BillingDtos.BillDto> generateBill(@PathVariable UUID orderId, @RequestParam long version,
                                                          @RequestParam(required = false) BigDecimal tipAmount,
                                                          @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(billingService.generateBill(orderId, version, tipAmount, userId(principal)));
    }

    /** Round 12 §11 - the Billing screen's "Back" action. See {@code BillingService#backToServed}'s
     * javadoc for exactly which states this is legal from and why it can never duplicate a bill or
     * payment. */
    @PostMapping("/orders/{orderId}/back")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<BillingDtos.BillDto> backToServed(@PathVariable UUID orderId, @RequestParam long version,
                                                          @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(billingService.backToServed(orderId, version, userId(principal)));
    }

    @PostMapping("/orders/{orderId}/payments")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<BillingDtos.BillDto> recordPayment(@PathVariable UUID orderId, @Valid @RequestBody BillingDtos.RecordPaymentRequest request,
                                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(billingService.recordPayment(orderId, request.method(), request.amount(), request.tenderedAmount(),
                request.referenceNumber(), request.tipAmount(), request.orderVersion(), userId(principal)));
    }

    @PostMapping("/orders/{orderId}/payments/{paymentId}/void")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<BillingDtos.BillDto> voidPayment(@PathVariable UUID orderId, @PathVariable UUID paymentId,
                                                         @Valid @RequestBody BillingDtos.VoidPaymentRequest request,
                                                         @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(billingService.voidPayment(orderId, paymentId, request.reason(), request.orderVersion(), userId(principal)));
    }

    @GetMapping("/orders/{orderId}/split")
    @PreAuthorize("hasAuthority('BILLING_MANAGE') or hasAuthority('ORDER_MODIFY') or hasAuthority('ORDER_MODIFY_OWN')")
    public ApiResponse<BillingDtos.SplitBillResponse> splitEvenly(@PathVariable UUID orderId, @RequestParam int ways) {
        return ApiResponse.ok(billingService.splitBillEvenly(orderId, ways));
    }

    @GetMapping("/orders/{orderId}/receipt")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<BillingDtos.ReceiptDto> receipt(@PathVariable UUID orderId) {
        return ApiResponse.ok(billingService.generateReceiptText(orderId));
    }

    /** Round 11 - "email receipt option". Re-generates the receipt text server-side (same source
     * {@link #receipt} uses - never trusts a client-supplied copy) and emails it via {@link
     * EmailReceiptService}, which throws a clear {@code ApiException} (SMTP not configured, bad
     * recipient, send failure) rather than ever silently dropping the send. */
    @PostMapping("/orders/{orderId}/receipt/email")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<Void> emailReceipt(@PathVariable UUID orderId, @Valid @RequestBody BillingDtos.EmailReceiptRequest request) {
        BillingDtos.ReceiptDto receipt = billingService.generateReceiptText(orderId);
        emailReceiptService.sendReceipt(request.toEmail(), receipt.orderNumber(), receipt.text());
        return ApiResponse.ok(null);
    }

    // ---- Cash management ----

    @PostMapping("/cash-movements")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<BillingDtos.CashMovementDto> recordCashMovement(@Valid @RequestBody BillingDtos.CreateCashMovementRequest request,
                                                                        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        CashMovement saved = billingService.recordCashMovement(request.type(), request.amount(), request.reason(), userId(principal));
        return ApiResponse.ok(toCashMovementDto(saved));
    }

    @GetMapping("/cash-summary")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<BillingDtos.CashSummaryDto> cashSummary(@RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(billingService.getCashSummary(date == null ? LocalDate.now() : date));
    }

    @GetMapping("/cash-movements")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<List<BillingDtos.CashMovementDto>> cashMovements(@RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(billingService.listCashMovements(date == null ? LocalDate.now() : date));
    }

    /** Round 13 (AI Backbone Addendum F1.5): logs a cash-drawer open not attached to any sale, so
     * NO_SALE_FREQUENCY has real data to count - see {@code BillingService#recordNoSale}'s javadoc. */
    @PostMapping("/no-sale")
    @PreAuthorize("hasAuthority('BILLING_MANAGE')")
    public ApiResponse<Void> recordNoSale(@RequestBody(required = false) BillingDtos.NoSaleRequest request,
                                           @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        billingService.recordNoSale(request == null ? null : request.reason(), userId(principal), null);
        return ApiResponse.ok(null);
    }

    // ---- mapping helpers ----

    private BillingDtos.TaxDto toTaxDto(Tax t) {
        return new BillingDtos.TaxDto(t.getId(), t.getName(), t.getCode(), t.getRatePercent(), t.isActive(), t.isDefaultRate(),
                t.getBranch() == null ? null : t.getBranch().getId(),
                t.getBranch() == null ? null : t.getBranch().getName(),
                t.getVersion());
    }

    private BillingDtos.DiscountDto toDiscountDto(Discount d) {
        return new BillingDtos.DiscountDto(d.getId(), d.getName(), d.getType().name(), d.getValue(), d.getMaxDiscountAmount(),
                d.getApplicableCategory() == null ? null : d.getApplicableCategory().getId(),
                d.getApplicableCategory() == null ? null : d.getApplicableCategory().getName(),
                d.isActive(),
                d.getBranch() == null ? null : d.getBranch().getId(),
                d.getBranch() == null ? null : d.getBranch().getName(),
                d.getVersion());
    }

    private BillingDtos.CashMovementDto toCashMovementDto(CashMovement m) {
        return new BillingDtos.CashMovementDto(m.getId(), m.getType().name(), m.getAmount(), m.getReason(),
                m.getRecordedBy() == null ? null : m.getRecordedBy().getDisplayName(), m.getCreatedAt());
    }

    private DiscountType parseType(String raw) {
        try {
            return DiscountType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("INVALID_DISCOUNT_TYPE", "Unknown discount type: " + raw);
        }
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
