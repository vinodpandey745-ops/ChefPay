package com.chefpay.core.domain;

/** Lifecycle of one {@link SupplierInvoice} (F2.3 - "OCR-Assisted Supplier Invoice Intake").
 * Matches the SRS's exact vocabulary (Section 8/F2.3 technical notes: "status:
 * PENDING_REVIEW/CONFIRMED/REJECTED"). Extraction (whether by Tesseract OCR, an AI vision assist,
 * or left fully blank for manual entry) only ever produces a PENDING_REVIEW draft - nothing updates
 * {@link InventoryItem#getCostPerUnit()} or stock until a manager explicitly confirms it, per F2.3's
 * "OCR output is never auto-applied without review." */
public enum SupplierInvoiceStatus {
    PENDING_REVIEW,
    CONFIRMED,
    REJECTED
}
