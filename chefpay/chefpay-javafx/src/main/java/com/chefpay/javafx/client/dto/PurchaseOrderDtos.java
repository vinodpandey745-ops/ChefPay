package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.purchasing.PurchaseOrderDtos} - Round 12 §12-§26. */
public final class PurchaseOrderDtos {

    private PurchaseOrderDtos() {
    }

    public record PurchaseOrderItemDto(UUID id, UUID inventoryItemId, String inventoryItemName, String unit,
                                        BigDecimal orderedQuantity, BigDecimal unitPrice, BigDecimal lineTotal,
                                        BigDecimal receivedQuantity, BigDecimal acceptedQuantity,
                                        BigDecimal damagedQuantity, BigDecimal rejectedQuantity,
                                        BigDecimal remainingQuantity, String receivingNotes, long version) {
    }

    public record PurchaseOrderDto(UUID id, String poNumber, UUID branchId, String branchName,
                                    UUID supplierId, String supplierName, String status,
                                    String createdByName, String approvedByName, LocalDateTime approvedAt,
                                    String rejectedByName, LocalDateTime rejectedAt, String rejectionReason,
                                    LocalDateTime submittedAt, LocalDateTime closedAt, String notes,
                                    BigDecimal totalAmount, List<PurchaseOrderItemDto> items,
                                    LocalDateTime createdAt, long version) {
    }

    public record CreatePurchaseOrderItemRequest(UUID inventoryItemId, BigDecimal orderedQuantity, BigDecimal unitPrice) {
    }

    public record CreatePurchaseOrderRequest(UUID branchId, UUID supplierId, String notes,
                                              List<CreatePurchaseOrderItemRequest> items) {
    }

    public record UpdatePurchaseOrderRequest(UUID supplierId, String notes, List<CreatePurchaseOrderItemRequest> items, long version) {
    }

    public record RejectPurchaseOrderRequest(String reason, long version) {
    }

    public record ShareRequest(String method, String recipient, long version) {
    }

    public record ReceiveLineRequest(UUID purchaseOrderItemId, BigDecimal receivedQuantity, BigDecimal acceptedQuantity,
                                      BigDecimal damagedQuantity, BigDecimal rejectedQuantity, String notes) {
    }

    public record ReceiveItemsRequest(List<ReceiveLineRequest> lines, long version) {
    }

    public record ShareLogDto(UUID id, String method, String recipient, String sentByName, LocalDateTime sentAt, String status) {
    }

    public record ReplenishmentSuggestionDto(UUID inventoryItemId, String itemName, String unit,
                                              BigDecimal quantityOnHand, BigDecimal reorderThreshold,
                                              BigDecimal pendingOrderedQuantity, BigDecimal suggestedQuantity, String reason) {
    }

    public record ReplenishmentSuggestionsResponse(List<ReplenishmentSuggestionDto> suggestions, String aiNarrative) {
    }

    /** Round 14 (F2.2). */
    public record CreateDraftPoFromSuggestionsRequest(UUID branchId, UUID supplierId, String notes,
                                                       List<CreatePurchaseOrderItemRequest> items) {
    }
}
