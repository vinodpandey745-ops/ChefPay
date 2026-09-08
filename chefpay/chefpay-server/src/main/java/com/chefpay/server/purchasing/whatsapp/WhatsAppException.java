package com.chefpay.server.purchasing.whatsapp;

/**
 * Internal failure from a WhatsApp Business API call (network error, non-2xx HTTP status,
 * unparseable response, ...). Mirrors {@code com.chefpay.server.ai.AiException} exactly - always
 * caught and translated into a {@code com.chefpay.server.purchasing.SupplierChannel.Result}-shaped
 * outcome by {@link WhatsAppService}; callers outside this package should never see a raw
 * {@code WhatsAppException} or its cause.
 */
public class WhatsAppException extends RuntimeException {

    public WhatsAppException(String message) {
        super(message);
    }

    public WhatsAppException(String message, Throwable cause) {
        super(message, cause);
    }
}
