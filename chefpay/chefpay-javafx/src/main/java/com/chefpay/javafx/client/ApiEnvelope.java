package com.chefpay.javafx.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Mirrors the server's {@code ApiResponse} envelope (ARCHITECTURE.md §11) on the client side. */
public record ApiEnvelope(
        boolean success,
        JsonNode data,
        String errorCode,
        String message,
        String timestamp,
        String correlationId
) {
}
