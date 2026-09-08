package com.chefpay.server.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class BillingDtos {

    private BillingDtos() {
    }

    // ---- Tax config ----

    /** Bistrodesk Phase 3 (requirement #6): {@code branchId}/{@code branchName} are null for the
     * GLOBAL rate for {@code code}, set for a branch-specific override - see {@code Tax.branch}. */
    public record TaxDto(UUID id, String name, String code, BigDecimal ratePercent, boolean active, boolean defaultRate,
                          UUID branchId, String branchName, long version) {
    }

    /** {@code branchId} null (the default) creates the GLOBAL rate for {@code code}; set creates
     * that branch's override instead - see {@code BillingService#createTax}. */
    public record CreateTaxRequest(@NotBlank String name, @NotBlank String code,
                                    @NotNull @Positive BigDecimal ratePercent, boolean defaultRate, UUID branchId) {
    }

    /** Any null field (except version) leaves that attribute unchanged. Deliberately no
     * branch-reassignment field here - a rate's branch is a one-time decision made at creation
     * (see {@code BillingService#updateTax}'s javadoc), not something edited casually. */
    public record UpdateTaxRequest(String name, BigDecimal ratePercent, Boolean active, Boolean defaultRate, long version) {
    }

    // ---- Discount presets ----

    /** Round 12: {@code maxDiscountAmount} (null = no cap) and {@code applicableCategoryId}/
     * {@code applicableCategoryName} (null = whole-bill, as before) - see {@code Discount}'s javadoc.
     * Bistrodesk Phase 3 (requirement #6): {@code branchId}/{@code branchName} are null for a
     * preset usable at every branch (every pre-existing preset), set for one restricted to a
     * single branch - see {@code Discount.branch}. */
    public record DiscountDto(UUID id, String name, String type, BigDecimal value, BigDecimal maxDiscountAmount,
                               UUID applicableCategoryId, String applicableCategoryName, boolean active,
                               UUID branchId, String branchName, long version) {
    }

    /** {@code branchId} null (the default) creates a preset usable at every branch; set restricts
     * it to that one branch - see {@code BillingService#createDiscount}. */
    public record CreateDiscountRequest(@NotBlank String name, @NotNull String type, @NotNull @Positive BigDecimal value,
                                         BigDecimal maxDiscountAmount, UUID applicableCategoryId, UUID branchId) {
    }

    /** Any null field (except version) leaves that attribute unchanged. {@code clearCategory}, when
     * true, removes an existing category scope (turns the preset back into a whole-bill discount) -
     * needed because a plain null {@code applicableCategoryId} already means "leave unchanged"
     * elsewhere in this record, so removal needs its own explicit signal, same as {@code
     * UpdateRestaurantRequest.logoImageBase64}'s blank-string-clears convention elsewhere in this
     * codebase (a UUID field has no blank-string equivalent to reuse). {@code clearBranch}/
     * {@code branchId} follow the identical convention for re-scoping the preset's branch. */
    public record UpdateDiscountRequest(String name, BigDecimal value, BigDecimal maxDiscountAmount,
                                         UUID applicableCategoryId, boolean clearCategory, Boolean active,
                                         UUID branchId, boolean clearBranch, long version) {
    }

    // ---- Bill / discount application / payments ----

    /** Exactly one of {@code discountId} or ({@code type} + {@code value}) must be supplied. */
    public record ApplyDiscountRequest(UUID discountId, String type, BigDecimal value, String reason, long orderVersion) {
    }

    public record TaxLineDto(String name, BigDecimal ratePercent, BigDecimal taxableAmount, BigDecimal amount) {
    }

    public record PaymentDto(UUID id, String method, BigDecimal amount, BigDecimal tenderedAmount, BigDecimal changeAmount,
                              String referenceNumber, String receiptNumber, String receivedByName, LocalDateTime receivedAt,
                              boolean voided, String voidReason, long version) {
    }

    public record BillDto(UUID orderId, String orderNumber, String orderStatus, String paymentStatus,
                           BigDecimal subtotal, BigDecimal discountAmount, String discountReason,
                           List<TaxLineDto> taxLines, BigDecimal taxAmount, BigDecimal serviceChargeAmount,
                           BigDecimal tipAmount, BigDecimal totalAmount, BigDecimal amountPaid, BigDecimal balanceDue,
                           List<PaymentDto> payments, long orderVersion) {
    }

    /** {@code amount} is required for CARD/UPI/WALLET/OTHER; {@code tenderedAmount} is required for CASH instead. */
    public record RecordPaymentRequest(@NotNull String method, BigDecimal amount, BigDecimal tenderedAmount,
                                        String referenceNumber, BigDecimal tipAmount, long orderVersion) {
    }

    public record VoidPaymentRequest(@NotBlank String reason, long orderVersion) {
    }

    public record SplitBillResponse(UUID orderId, int ways, BigDecimal totalAmount, List<BigDecimal> shares) {
    }

    public record ReceiptDto(String orderNumber, String text) {
    }

    /** Round 11 - "email receipt" action's request body. */
    public record EmailReceiptRequest(@NotBlank String toEmail) {
    }

    // ---- Cash management ----

    public record CreateCashMovementRequest(@NotNull String type, @NotNull @Positive BigDecimal amount, @NotBlank String reason) {
    }

    public record CashMovementDto(UUID id, String type, BigDecimal amount, String reason, String recordedByName, LocalDateTime recordedAt) {
    }

    public record CashSummaryDto(LocalDate date, BigDecimal totalCashPayments, BigDecimal totalCashIn,
                                  BigDecimal totalCashOut, BigDecimal expectedCashInDrawer) {
    }

    /** Round 13: optional free-text note for a "No Sale" drawer open - see {@code
     * BillingService#recordNoSale}. Body may be omitted entirely (null reason). */
    public record NoSaleRequest(String reason) {
    }
}
