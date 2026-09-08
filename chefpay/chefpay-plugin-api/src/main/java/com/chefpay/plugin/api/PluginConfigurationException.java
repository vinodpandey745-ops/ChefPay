package com.chefpay.plugin.api;

/** Thrown by a plugin during {@link ChefPayPlugin#init(PluginContext)} when its configuration is invalid or incomplete. */
public class PluginConfigurationException extends Exception {
    public PluginConfigurationException(String message) {
        super(message);
    }

    public PluginConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
