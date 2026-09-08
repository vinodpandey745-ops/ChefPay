package com.chefpay.javafx.client;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where to find chefpay-server. Three ways to configure this, checked in order, so a packaged
 * (jpackage) install never needs a rebuild to point at a real restaurant server:
 *
 * <ol>
 *   <li>{@code -Dchefpay.server.http=...} / {@code -Dchefpay.server.ws=...} JVM system properties -
 *       full URL override, for local dev (`mvn javafx:run -Dchefpay.server.http=...`) or anyone
 *       who wants to hand-edit the launcher's JVM args directly.</li>
 *   <li>A {@code chefpay-client.properties} file (see {@link #CONFIG_FILE_NAME}) with plain
 *       {@code server.host}/{@code server.port}/{@code server.scheme} keys - this is the one meant
 *       for restaurant staff: no URLs, no {@code -D} flags, just an address to type in. Looked for
 *       first next to wherever the app was launched from (the working directory - this is the
 *       install folder for a normal double-clicked jpackage exe/shortcut), then in the current
 *       user's home directory as a fallback (lets one Windows user override the shared install's
 *       config without touching the install folder, e.g. on a shared/kiosk machine).</li>
 *   <li>{@code http://localhost:8080} / {@code ws://localhost:8080/ws} - the original hardcoded
 *       default, unchanged, for local dev with the server running on the same machine.</li>
 * </ol>
 *
 * See {@code docs/DEPLOYMENT.md} for the full client-packaging + config-file walkthrough.
 */
public final class ServerConfig {

    static final String CONFIG_FILE_NAME = "chefpay-client.properties";

    private static final String DEFAULT_HTTP = "http://localhost:8080";
    private static final String DEFAULT_WS = "ws://localhost:8080/ws";

    /** Loaded once per JVM run (this config never changes while the app is open) and reused by
     * both {@link #httpBaseUrl()} and {@link #wsUrl()} so they're always derived consistently from
     * the same source, rather than each independently re-checking the filesystem. */
    private static final Properties FILE_CONFIG = loadConfigFile();

    private ServerConfig() {
    }

    public static String httpBaseUrl() {
        String override = System.getProperty("chefpay.server.http");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String host = FILE_CONFIG.getProperty("server.host");
        if (host == null || host.isBlank()) {
            return DEFAULT_HTTP;
        }
        String scheme = "https".equalsIgnoreCase(FILE_CONFIG.getProperty("server.scheme", "http")) ? "https" : "http";
        return scheme + "://" + host.trim() + portSuffix(scheme);
    }

    public static String wsUrl() {
        String override = System.getProperty("chefpay.server.ws");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String host = FILE_CONFIG.getProperty("server.host");
        if (host == null || host.isBlank()) {
            return DEFAULT_WS;
        }
        String scheme = "https".equalsIgnoreCase(FILE_CONFIG.getProperty("server.scheme", "http")) ? "https" : "http";
        String wsScheme = "https".equals(scheme) ? "wss" : "ws";
        return wsScheme + "://" + host.trim() + portSuffix(scheme) + "/ws";
    }

    /** Omits the port entirely when it's the default for the scheme (80 for http, 443 for https) -
     * so a restaurant using a real domain behind a reverse proxy on standard ports can just set
     * {@code server.host} and {@code server.scheme}, with no port to get wrong. */
    private static String portSuffix(String scheme) {
        String portRaw = FILE_CONFIG.getProperty("server.port");
        if (portRaw == null || portRaw.isBlank()) {
            return "";
        }
        String port = portRaw.trim();
        boolean isDefaultHttp = "http".equals(scheme) && "80".equals(port);
        boolean isDefaultHttps = "https".equals(scheme) && "443".equals(port);
        return (isDefaultHttp || isDefaultHttps) ? "" : ":" + port;
    }

    private static Properties loadConfigFile() {
        Properties props = new Properties();
        for (Path candidate : candidatePaths()) {
            if (Files.isRegularFile(candidate)) {
                try (InputStream in = new FileInputStream(candidate.toFile())) {
                    props.load(in);
                    return props;
                } catch (IOException ignored) {
                    // Fall through to the next candidate (or the hardcoded default) rather than
                    // block startup over an unreadable/malformed config file.
                }
            }
        }
        return props;
    }

    private static Path[] candidatePaths() {
        return new Path[] {
                Path.of(System.getProperty("user.dir", "."), CONFIG_FILE_NAME),
                Path.of(System.getProperty("user.home", "."), CONFIG_FILE_NAME)
        };
    }
}
