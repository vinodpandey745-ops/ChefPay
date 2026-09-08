package com.chefpay.core.domain;

/**
 * How a Purchase Order document was shared with its supplier (Round 12 §19-§20). {@code API} is
 * deliberately included even though no real supplier API integration exists yet ({@link
 * com.chefpay.core.domain.Supplier} is offline/manual-only today) - it's here so {@link
 * PurchaseOrderShareLog}'s shape never has to change when a future supplier integration adds
 * itself as a new channel; that channel would simply start writing {@code API} rows through the
 * same audit log the manual channels already use.
 */
public enum PurchaseOrderShareMethod {
    PRINT,
    EMAIL,
    WHATSAPP,
    API
}
