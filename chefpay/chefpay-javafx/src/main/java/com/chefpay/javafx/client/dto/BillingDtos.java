package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class BillingDtos {

    private BillingDtos() {
    }

    public record TaxDto(UUID id, String name, String code, BigDecimal ratePercent, boolean active, boolean defaultRate, long version) {
    }

    public record CreateTaxRequest(String name, String code, BigDecimal ratePercent, boolean defaultRate) {
    }

    /** Any null field (except version) leaves that attribute unchanged - matches the server record. */
    public record UpdateTaxRequest(String name, BigDecimal ratePercent, Boolean active, Boolean defaultRate, long version) {
    }

    public record DiscountDto(UUID id, String name, String type, BigDecimal value, BigDecimal maxDiscountAmount,
                               UUID applicableCategoryId, String applicableCategoryName, boolean active, long version) {
    }

    public record CreateDiscountRequest(String name, String type, BigDecimal value, BigDecimal maxDiscountAmount,
                                         UUID applicableCategoryId) {
    }

    public record UpdateDiscountRequest(String name, BigDecimal value, BigDecimal maxDiscountAmount,
                                         UUID applicableCategoryId, boolean clearCategory, Boolean active, long version) {
    }

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

    public record RecordPaymentRequest(String method, BigDecimal amount, BigDecimal tenderedAmount,
                                        String referenceNumber, BigDecimal tipAmount, long orderVersion) {
    }

    public record VoidPaymentRequest(String reason, long orderVersion) {
    }

    public record SplitBillResponse(UUID orderId, int ways, BigDecimal totalAmount, List<BigDecimal> shares) {
    }

    public record ReceiptDto(String orderNumber, String text) {
    }

    /** Round 11 - "email receipt" action's request body. */
    public record EmailReceiptRequest(String toEmail) {
    }

    // ---- Cash management ----

    public record CreateCashMovementRequest(String type, BigDecimal amount, String reason) {
    }

    public record CashMovementDto(UUID id, String type, BigDecimal amount, String reason, String recordedByName, LocalDateTime recordedAt) {
    }

    public record CashSummaryDto(LocalDate date, BigDecimal totalCashPayments, BigDecimal totalCashIn,
                                  BigDecimal totalCashOut, BigDecimal expectedCashInDrawer) {
    }

    /** Round 13 (AI Backbone Addendum F1.5) - "No Sale" drawer-open button on {@code BillingView},
     * so the NO_SALE_FREQUENCY fraud rule has real events to count. Reason is optional. */
    public record NoSaleRequest(String reason) {
    }
}
