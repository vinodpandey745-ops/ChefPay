package com.chefpay.server.common;

import org.springframework.http.HttpStatus;

/** Thrown by services/controllers when a request fails a business rule; translated to the standard error envelope. */
public class ApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public ApiException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    public static ApiException badRequest(String errorCode, String message) {
        return new ApiException(errorCode, message, HttpStatus.BAD_REQUEST);
    }

    public static ApiException conflict(String errorCode, String message) {
        return new ApiException(errorCode, message, HttpStatus.CONFLICT);
    }

    public static ApiException forbidden(String message) {
        return new ApiException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException("UNAUTHORIZED", message, HttpStatus.UNAUTHORIZED);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
