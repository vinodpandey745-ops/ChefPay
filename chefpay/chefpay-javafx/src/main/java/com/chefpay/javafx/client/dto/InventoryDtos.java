package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record ItemDto(UUID id, String name, String unit, BigDecimal quantityOnHand, BigDecimal reorderThreshold,
                           BigDecimal costPerUnit, boolean lowStock, boolean active, long version,
                           UUID preferredSupplierId, String preferredSupplierName) {
    }

    public record CreateItemRequest(String name, String unit, BigDecimal openingQuantity,
                                     BigDecimal reorderThreshold, BigDecimal costPerUnit) {
    }

    /** Round 14 (F3.1). */
    public record SetPreferredSupplierRequest(UUID supplierId, long version) {
    }

    public record RecordTransactionRequest(String type, BigDecimal quantity, String reason, long itemVersion) {
    }

    public record TransactionDto(UUID id, UUID itemId, String type, BigDecimal quantity, BigDecimal resultingQuantity,
                                  String reason, String recordedByName, LocalDateTime createdAt) {
    }
}
