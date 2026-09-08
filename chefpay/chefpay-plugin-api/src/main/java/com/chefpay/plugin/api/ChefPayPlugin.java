package com.chefpay.plugin.api;

import java.util.List;

/**
 * Root interface every ChefPay plugin implements.
 *
 * <p>Plugins are discovered two ways, both supported simultaneously:
 * <ol>
 *   <li>Java {@link java.util.ServiceLoader} - a jar on the classpath declares its
 *       implementation in {@code META-INF/services/com.chefpay.plugin.api.ChefPayPlugin}.
 *       This is how the bundled plugins (WhatsApp, demo online-order, etc.) are found.</li>
 *   <li>Spring bean registration - any {@code @Component} implementing this interface
 *       is picked up automatically by {@code PluginManager}. This makes it possible to
 *       plug in a new capability with a single class and zero other wiring.</li>
 * </ol>
 *
 * <p>Every plugin is independently enable/disable-able at runtime from
 * Settings &gt; Plugins. Disabled plugins are never invoked. Nothing in core
 * ChefPay ever hard-fails because a plugin is missing or disabled - all
 * extension points are optional by design (see class-level docs on each
 * sub-interface).
 */
public interface ChefPayPlugin {

    /**
     * Stable, unique identifier, e.g. {@code "whatsapp-meta-cloud"}. Used as the
     * key under which this plugin's enabled flag and configuration are persisted.
     */
    String getId();

    /** Human-readable name shown in Settings &gt; Plugins. */
    String getName();

    /** Short description shown in Settings &gt; Plugins. */
    default String getDescription() {
        return "";
    }

    /** Semantic version string, e.g. "1.0.0". */
    default String getVersion() {
        return "1.0.0";
    }

    /** Which capability category this plugin extends. Used to group the plugin list in the UI. */
    PluginCategory getCategory();

    /**
     * Declarative config fields this plugin needs (API keys, endpoints, toggles...).
     * The Settings &gt; Plugins screen renders a form from this list generically -
     * no plugin-specific UI code is required in chefpay-ui. Values entered by the
     * user are persisted (secrets encrypted at rest) and handed back via
     * {@link #init(PluginContext)}.
     */
    default List<ConfigField> getConfigSchema() {
        return List.of();
    }

    /**
     * Called once when the plugin is enabled (at app startup if already enabled,
     * or immediately when the user flips it on). Implementations should validate
     * configuration here and throw {@link PluginConfigurationException} if
     * required fields are missing/invalid so the UI can surface a clear error.
     */
    default void init(PluginContext context) throws PluginConfigurationException {
        // no-op by default
    }

    /** Called when the plugin is disabled or the application is shutting down. */
    default void shutdown() {
        // no-op by default
    }

    /** Lightweight self-check used by the Settings screen's "Test connection" button. */
    default HealthStatus healthCheck() {
        return HealthStatus.unknown();
    }
}
