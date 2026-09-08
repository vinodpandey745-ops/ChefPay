package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.invoices.SupplierInvoiceDtos} (F2.3). */
public final class SupplierInvoiceDtos {

    private SupplierInvoiceDtos() {
    }

    public record SupplierInvoiceLineDto(UUID id, UUID inventoryItemId, String inventoryItemName, String description,
                                          BigDecimal quantity, BigDecimal unitCost, String rawText) {
    }

    public record SupplierInvoiceDto(UUID id, UUID supplierId, String supplierName, String extractionMethod,
                                      String extractedRawText, String status, String notes, String confirmedByName,
                                      LocalDateTime confirmedAt, List<SupplierInvoiceLineDto> lines,
                                      LocalDateTime createdAt, long version) {
    }

    public record ScanRequest(String imageBase64, String mimeType, UUID supplierId) {
    }

    public record ConfirmLineRequest(UUID inventoryItemId, String description, BigDecimal quantity, BigDecimal unitCost) {
    }

    public record ConfirmInvoiceRequest(List<ConfirmLineRequest> lines, String notes, long version) {
    }

    public record RejectInvoiceRequest(String notes, long version) {
    }
}
