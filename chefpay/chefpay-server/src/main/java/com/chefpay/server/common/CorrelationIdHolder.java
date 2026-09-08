package com.chefpay.server.common;

/** Thread-local holder so any layer (controller, service, exception handler) can read the current request's correlation id for logging/response purposes. */
public final class CorrelationIdHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationIdHolder() {
    }

    public static void set(String correlationId) {
        CURRENT.set(correlationId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
