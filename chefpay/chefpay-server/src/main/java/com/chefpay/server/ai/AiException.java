package com.chefpay.server.ai;

/**
 * Internal failure from an AI provider call (network error, non-2xx HTTP status, unparseable
 * response, ...). Always caught and translated to a client-facing {@code ApiException} with error
 * code {@code AI_PROVIDER_ERROR} by {@link AiService} - callers outside this package should never
 * see a raw {@code AiException} or its cause.
 */
public class AiException extends RuntimeException {

    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
