package com.chefpay.plugin.api;

import java.util.Map;
import java.util.Optional;

/**
 * Handed to a plugin at {@link ChefPayPlugin#init(PluginContext)}. Gives the plugin
 * access to its own persisted configuration and a couple of host-provided utilities,
 * without exposing any internal ChefPay core classes (keeps the plugin API surface small
 * and stable across core refactors).
 */
public interface PluginContext {

    /** Raw config values entered by the user for this plugin (see {@link ConfigField}). */
    Map<String, String> config();

    default Optional<String> configValue(String key) {
        return Optional.ofNullable(config().get(key));
    }

    default String requireConfigValue(String key) throws PluginConfigurationException {
        String value = config().get(key);
        if (value == null || value.isBlank()) {
            throw new PluginConfigurationException("Missing required config value: " + key);
        }
        return value;
    }

    /** Restaurant-wide display name / currency, useful for message templates and receipts. */
    RestaurantInfo restaurantInfo();

    /** Simple logger sink so plugin log lines show up in the app's plugin log panel. */
    PluginLogger logger();
}
