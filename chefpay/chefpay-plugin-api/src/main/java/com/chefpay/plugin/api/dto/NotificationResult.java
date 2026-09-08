package com.chefpay.plugin.api.dto;

public record NotificationResult(boolean success, String message) {
    public static NotificationResult ok(String message) {
        return new NotificationResult(true, message);
    }

    public static NotificationResult failure(String message) {
        return new NotificationResult(false, message);
    }
}
