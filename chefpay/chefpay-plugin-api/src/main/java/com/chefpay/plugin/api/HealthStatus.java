package com.chefpay.plugin.api;

public record HealthStatus(State state, String message) {

    public enum State { OK, WARNING, ERROR, UNKNOWN }

    public static HealthStatus ok(String message) {
        return new HealthStatus(State.OK, message);
    }

    public static HealthStatus error(String message) {
        return new HealthStatus(State.ERROR, message);
    }

    public static HealthStatus unknown() {
        return new HealthStatus(State.UNKNOWN, "Not checked");
    }
}
