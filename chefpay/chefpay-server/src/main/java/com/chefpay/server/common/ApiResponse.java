package com.chefpay.server.common;

import java.time.LocalDateTime;

/** Standard success/error envelope for every REST response (ARCHITECTURE.md §11). */
public record ApiResponse<T>(
        boolean success,
        T data,
        String errorCode,
        String message,
        LocalDateTime timestamp,
        String correlationId
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, LocalDateTime.now(), CorrelationIdHolder.get());
    }

    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(false, null, errorCode, message, LocalDateTime.now(), CorrelationIdHolder.get());
    }
}
