package com.chefpay.plugin.api;

/**
 * Describes one configuration value a plugin needs. The Settings &gt; Plugins screen
 * renders an input for each field declared by {@link ChefPayPlugin#getConfigSchema()}.
 *
 * @param key          storage key, e.g. "accessToken"
 * @param label        UI label, e.g. "Permanent Access Token"
 * @param type         controls which input widget is rendered
 * @param required     whether the plugin can be enabled without this value
 * @param secret       if true, value is masked in the UI and encrypted at rest
 * @param helpText     short helper text shown under the field
 * @param defaultValue pre-filled value, or null
 */
public record ConfigField(
        String key,
        String label,
        ConfigFieldType type,
        boolean required,
        boolean secret,
        String helpText,
        String defaultValue
) {
    public static ConfigField text(String key, String label, boolean required, String helpText) {
        return new ConfigField(key, label, ConfigFieldType.TEXT, required, false, helpText, null);
    }

    public static ConfigField secret(String key, String label, boolean required, String helpText) {
        return new ConfigField(key, label, ConfigFieldType.TEXT, required, true, helpText, null);
    }

    public static ConfigField bool(String key, String label, String defaultValue, String helpText) {
        return new ConfigField(key, label, ConfigFieldType.BOOLEAN, false, false, helpText, defaultValue);
    }

    public static ConfigField number(String key, String label, boolean required, String defaultValue, String helpText) {
        return new ConfigField(key, label, ConfigFieldType.NUMBER, required, false, helpText, defaultValue);
    }
}
