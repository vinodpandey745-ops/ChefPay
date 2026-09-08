package com.chefpay.server.purchasing;

import com.chefpay.core.domain.PurchaseOrder;

/**
 * Round 12 §18 - the "supplier-abstraction interface for future API integration ... offline
 * today, pluggable APIs later, explicitly not tightly coupled" requirement.
 *
 * <p>Print/Email/WhatsApp are NOT modeled through this interface - they follow the exact same
 * split the existing receipt-sharing feature already uses (see {@code ReceiptPrinter}/{@code
 * WhatsAppSender}'s javadoc): Print and WhatsApp are client-side-only actions the JavaFX app
 * performs directly (open a print dialog, open a {@code wa.me} deep link) with no server
 * involvement beyond generating the document text, and Email goes through the existing {@code
 * EmailReceiptService} the same way a billing receipt email does. This interface exists purely
 * for the one channel that doesn't exist yet: a real supplier API integration. Today {@link
 * #send} always comes back "not configured" via {@link UnavailableSupplierApiChannel} - see its
 * javadoc for why that's an honest, deliberate placeholder rather than a fake success. A future
 * integration adds a new implementation and wires it in (e.g. per-supplier credentials on a new
 * {@link com.chefpay.core.domain.Supplier} field) without {@link PurchaseOrderService} or any
 * existing channel changing shape at all.
 */
public interface SupplierChannel {

    Result send(PurchaseOrder purchaseOrder, String recipient, String documentText);

    record Result(boolean success, String statusMessage) {
    }
}
