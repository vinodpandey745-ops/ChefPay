package com.chefpay.server.invoices;

import com.chefpay.core.domain.SupplierInvoice;
import com.chefpay.core.domain.SupplierInvoiceLine;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * F2.3 - OCR-assisted supplier invoice intake. Scanning/viewing needs only {@code INVENTORY_VIEW}
 * (the same audience that can already see stock levels); confirming or rejecting - the step that
 * actually writes to inventory - needs {@code INVENTORY_MANAGE}, matching every other
 * inventory-mutating endpoint in this codebase.
 *
 * <p>Bistrodesk Phase 4 (requirement #24): gated on {@code INVENTORY_MANAGEMENT} (not {@code
 * PURCHASE_ORDERS}) - this endpoint's whole job is writing to inventory stock/cost from a scanned
 * invoice (same {@code INVENTORY_VIEW}/{@code INVENTORY_MANAGE} audience as {@link
 * com.chefpay.server.inventory.InventoryController} above), independent of whether the install
 * uses this codebase's own Purchase Order workflow at all.
 */
@RestController
@RequestMapping("/api/inventory/invoices")
@RequiredArgsConstructor
@RequiresFeature("INVENTORY_MANAGEMENT")
public class SupplierInvoiceController {

    private final SupplierInvoiceService supplierInvoiceService;

    @PostMapping("/scan")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<SupplierInvoiceDtos.SupplierInvoiceDto> scan(@RequestBody SupplierInvoiceDtos.ScanRequest request,
                                                                     @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        SupplierInvoice saved = supplierInvoiceService.scan(request.imageBase64(), request.mimeType(), request.supplierId(), userId(principal));
        return ApiResponse.ok(toDto(saved));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('INVENTORY_VIEW') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<List<SupplierInvoiceDtos.SupplierInvoiceDto>> list() {
        return ApiResponse.ok(supplierInvoiceService.listAll().stream().map(this::toDto).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW') or hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<SupplierInvoiceDtos.SupplierInvoiceDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(toDto(supplierInvoiceService.get(id)));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<SupplierInvoiceDtos.SupplierInvoiceDto> confirm(@PathVariable UUID id,
                                                                        @RequestBody SupplierInvoiceDtos.ConfirmInvoiceRequest request,
                                                                        @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        SupplierInvoice saved = supplierInvoiceService.confirm(id, request.lines(), request.notes(), request.version(), userId(principal));
        return ApiResponse.ok(toDto(saved));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
    public ApiResponse<SupplierInvoiceDtos.SupplierInvoiceDto> reject(@PathVariable UUID id,
                                                                       @RequestBody SupplierInvoiceDtos.RejectInvoiceRequest request,
                                                                       @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        SupplierInvoice saved = supplierInvoiceService.reject(id, request.notes(), request.version(), userId(principal));
        return ApiResponse.ok(toDto(saved));
    }

    private SupplierInvoiceDtos.SupplierInvoiceDto toDto(SupplierInvoice invoice) {
        List<SupplierInvoiceDtos.SupplierInvoiceLineDto> lines = invoice.getLines().stream()
                .map(this::toLineDto).toList();
        return new SupplierInvoiceDtos.SupplierInvoiceDto(invoice.getId(),
                invoice.getSupplier() == null ? null : invoice.getSupplier().getId(),
                invoice.getSupplier() == null ? null : invoice.getSupplier().getName(),
                invoice.getExtractionMethod(), invoice.getExtractedRawText(), invoice.getStatus().name(),
                invoice.getNotes(), invoice.getConfirmedBy() == null ? null : invoice.getConfirmedBy().getDisplayName(),
                invoice.getConfirmedAt(), lines, invoice.getCreatedAt(), invoice.getVersion());
    }

    private SupplierInvoiceDtos.SupplierInvoiceLineDto toLineDto(SupplierInvoiceLine line) {
        return new SupplierInvoiceDtos.SupplierInvoiceLineDto(line.getId(),
                line.getInventoryItem() == null ? null : line.getInventoryItem().getId(),
                line.getInventoryItem() == null ? null : line.getInventoryItem().getName(),
                line.getDescription(), line.getQuantity(), line.getUnitCost(), line.getRawText());
    }

    private UUID userId(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
