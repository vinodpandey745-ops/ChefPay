package com.chefpay.server.invoices;

/**
 * What one extraction attempt (Tesseract OCR or the AI vision assist) produced - {@code
 * available=false} covers every failure mode uniformly (no native Tesseract library on this
 * machine, no tessdata configured, a corrupt/unreadable image, an AI provider error) so callers
 * never need to distinguish exception types, only "did we get text or not."
 */
public record OcrExtractionResult(boolean available, String rawText, String method, String unavailableReason) {

    public static OcrExtractionResult success(String rawText, String method) {
        return new OcrExtractionResult(true, rawText, method, null);
    }

    public static OcrExtractionResult unavailable(String reason) {
        return new OcrExtractionResult(false, null, "MANUAL", reason);
    }
}
