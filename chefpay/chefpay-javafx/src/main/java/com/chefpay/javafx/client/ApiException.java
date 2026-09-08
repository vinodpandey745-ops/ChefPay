package com.chefpay.javafx.client;

/** Raised when the server responds with {@code success: false}, or the call fails entirely (e.g. server unreachable). */
public class ApiException extends RuntimeException {

    private final String errorCode;

    public ApiException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "NETWORK_ERROR";
    }

    public String getErrorCode() {
        return errorCode;
    }
}
