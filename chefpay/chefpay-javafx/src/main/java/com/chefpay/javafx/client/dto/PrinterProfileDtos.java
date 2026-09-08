package com.chefpay.javafx.client.dto;

import java.util.UUID;

/** Mirrors {@code com.chefpay.server.printers.PrinterProfileDtos} (Round 8 Printer Listing screen). */
public final class PrinterProfileDtos {

    private PrinterProfileDtos() {
    }

    public record PrinterProfileDto(UUID id, UUID branchId, String name, String printerName, boolean forBill,
                                     boolean forKot, boolean forEbill, boolean active, long version) {
    }

    public record CreatePrinterProfileRequest(UUID branchId, String name, String printerName,
                                               boolean forBill, boolean forKot, boolean forEbill) {
    }

    /** Any null field (except version) leaves that attribute unchanged. */
    public record UpdatePrinterProfileRequest(String name, String printerName, Boolean forBill, Boolean forKot,
                                               Boolean forEbill, Boolean active, long version) {
    }
}
