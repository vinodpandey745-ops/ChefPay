package com.chefpay.server.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record ItemDto(UUID id, String name, String unit, BigDecimal quantityOnHand, BigDecimal reorderThreshold,
                           BigDecimal costPerUnit, boolean lowStock, boolean active, long version,
                           UUID preferredSupplierId, String preferredSupplierName,
                           UUID branchId, String branchName) {
    }

    /** Round 14 (F3.1). */
    public record SetPreferredSupplierRequest(UUID supplierId, long version) {
    }

    /** {@code branchId} (Bistrodesk Phase 2) is optional - see {@code InventoryController}'s own
     * javadoc for the resolution order when omitted (a single-branch install needs no frontend
     * change at all). */
    public record CreateItemRequest(@NotBlank String name, @NotBlank String unit, BigDecimal openingQuantity,
                                     BigDecimal reorderThreshold, BigDecimal costPerUnit, UUID branchId) {
    }

    /** Any null field (except version) leaves that attribute unchanged; stock quantity is never edited here - use a transaction. */
    public record UpdateItemRequest(String name, String unit, BigDecimal reorderThreshold, BigDecimal costPerUnit,
                                     Boolean active, long version) {
    }

    public record RecordTransactionRequest(@NotNull String type, @NotNull @Positive BigDecimal quantity,
                                            @NotBlank String reason, long itemVersion) {
    }

    public record TransactionDto(UUID id, UUID itemId, String type, BigDecimal quantity, BigDecimal resultingQuantity,
                                  String reason, String recordedByName, LocalDateTime createdAt) {
    }
}
