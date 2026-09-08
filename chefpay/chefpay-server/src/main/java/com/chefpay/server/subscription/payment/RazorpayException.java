package com.chefpay.server.subscription.payment;

/**
 * Internal failure from a Razorpay API call (unconfigured credentials, network error, non-2xx
 * HTTP status, unparseable response, ...). Mirrors {@code
 * com.chefpay.server.purchasing.whatsapp.WhatsAppException} exactly - callers outside this package
 * should never see a raw {@code RazorpayException} or its cause; translate it into a clear
 * {@code ApiException} at the controller boundary instead.
 */
public class RazorpayException extends RuntimeException {

    public RazorpayException(String message) {
        super(message);
    }

    public RazorpayException(String message, Throwable cause) {
        super(message, cause);
    }
}
