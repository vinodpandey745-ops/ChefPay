package com.chefpay.javafx.client;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

/**
 * Round 11: the local SQLite cache behind "sync with server on first login of the day (per
 * network availability), then store important info locally so the POS runs lightly if offline" -
 * opens/creates {@code ~/.chefpay/local.db} on first use. Mirrors RetailPOS's
 * {@code com.retailpos.javafx.client.LocalDatabase} bootstrap pattern (plain JDBC
 * {@link Statement#execute}, no client-side Flyway) but with a deliberately simpler shape for this
 * app's actual need: a read-through cache, not a typed local replica of every column.
 *
 * <p>Two tables:
 * <ul>
 *   <li>{@code cached_payload} - one row per cache key ({@code "menu"}, {@code "tables"},
 *       {@code "restaurant"}), storing the exact JSON this screen last successfully fetched from
 *       the server. {@link SyncEngine} refreshes these on login and whenever connectivity returns;
 *       {@code TableMatrixView}/{@code OrderTakingView} fall back to reading them only when a live
 *       {@code GET} fails. Storing the raw JSON blob (rather than modeling menu/table columns one
 *       by one) means this cache never needs its own migration when a DTO gains a field - it's
 *       exactly whatever the server last sent, deserialized the same way the live path already
 *       does.</li>
 *   <li>{@code sync_queue} - the same offline-mutation outbox shape RetailPOS's {@code
 *       LocalDatabase} defines, kept here for the same reason it exists there today: a real,
 *       working table and {@link SyncEngine} push loop ready for a future mutation type to enqueue
 *       into, without every write in this app needing to become offline-safe in the same pass as
 *       the read-cache. Nothing in ChefPay calls {@link #insertQueueItem} yet - seeing this queue
 *       sit empty is expected, not a bug, exactly mirroring RetailPOS's own current state.</li>
 * </ul>
 */
public final class LocalDatabase {

    private static volatile boolean initialized = false;
    private static String jdbcUrl;

    private LocalDatabase() {
    }

    /** Idempotent - safe to call once at app startup ({@code ChefPayDesktopApp}); a second call is a no-op. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        Path dbDir = Path.of(System.getProperty("user.home"), ".chefpay");
        try {
            java.nio.file.Files.createDirectories(dbDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not create local data directory at " + dbDir, e);
        }
        Path dbFile = dbDir.resolve("local.db");
        // Round 27 (pre-deployment audit) fix: every call here (putCached/readCached/
        // insertQueueItem/countPending) opens its own short-lived Connection via connect() rather
        // than sharing one - a JavaFX UI-thread call and a SyncEngine background-thread call can
        // land on this file at the same instant, and SQLite's own default busy_timeout is 0ms (fail
        // immediately with SQLITE_BUSY rather than wait). putCached's catch block intentionally
        // swallows failures as "best-effort" (see its javadoc) and readCached's/countPending's
        // catch blocks intentionally treat any SQLException as "nothing cached" - both reasonable
        // for a read-through cache, except that without a busy_timeout, ordinary same-instant
        // read/write overlap (not just a real error) could silently look identical to "nothing
        // cached" or a dropped write. Same 30s value and reasoning as the server's own
        // SqliteDataSourceConfig; this file is a single-writer-at-a-time cache with no cross-
        // connection REQUIRES_NEW-style pattern to worry about, so busy_timeout alone (no WAL) is
        // sufficient here too.
        jdbcUrl = "jdbc:sqlite:" + dbFile + "?busy_timeout=30000";

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS cached_payload (
                        cache_key TEXT PRIMARY KEY,
                        payload_json TEXT NOT NULL,
                        cached_at TEXT NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sync_queue (
                        id TEXT PRIMARY KEY,
                        idempotency_key TEXT NOT NULL UNIQUE,
                        entity_type TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        attempts INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT,
                        created_at TEXT NOT NULL,
                        synced_at TEXT
                    )
                    """);

            initialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize local SQLite database at " + dbFile, e);
        }
    }

    private static Connection connect() throws SQLException {
        if (!initialized) {
            init();
        }
        return DriverManager.getConnection(jdbcUrl);
    }

    /** Overwrites whatever was cached under {@code cacheKey} - callers pass the exact JSON string
     * a successful {@code ApiClient} response body would have contained, so a later {@link
     * #readCached} round-trips through the same Jackson deserialization the live path uses. */
    public static void putCached(String cacheKey, String payloadJson) {
        String sql = """
                INSERT INTO cached_payload (cache_key, payload_json, cached_at) VALUES (?, ?, ?)
                ON CONFLICT(cache_key) DO UPDATE SET payload_json = excluded.payload_json, cached_at = excluded.cached_at
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cacheKey);
            statement.setString(2, payloadJson);
            statement.setString(3, LocalDateTime.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            // Caching is best-effort - a failed write here shouldn't block whatever screen just
            // successfully loaded live data (the cache is a fallback, not the source of truth).
        }
    }

    /** Returns the last cached row for {@code cacheKey}, or {@code null} if nothing has ever been
     * cached under that key (e.g. this terminal has never successfully reached the server, or this
     * is a fresh install) - callers must treat {@code null} as "no offline fallback available",
     * exactly the "never silently pretend there's data" rule every other cache in this app follows. */
    public static CachedPayload readCached(String cacheKey) {
        String sql = "SELECT payload_json, cached_at FROM cached_payload WHERE cache_key = ?";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cacheKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new CachedPayload(rs.getString("payload_json"), rs.getString("cached_at"));
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Appends a new {@code PENDING} outbox row - not yet called by anything in this app (see class
     * javadoc). {@code idempotencyKey} must be generated once at mutation-creation time and never
     * regenerated on retry, matching RetailPOS's identical convention.
     */
    public static void insertQueueItem(String idempotencyKey, String entityType, String payloadJson) {
        String sql = """
                INSERT INTO sync_queue (id, idempotency_key, entity_type, payload_json, status, attempts, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?)
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, java.util.UUID.randomUUID().toString());
            statement.setString(2, idempotencyKey);
            statement.setString(3, entityType);
            statement.setString(4, payloadJson);
            statement.setString(5, LocalDateTime.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not append to the local sync queue.", e);
        }
    }

    /** Count of {@code PENDING} outbox rows - always 0 today (see class javadoc), kept for parity
     * with RetailPOS's status-chip wiring so a future caller of {@link #insertQueueItem} has
     * somewhere to report progress without touching {@code SyncEngine} again. */
    public static int countPending() {
        String sql = "SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'";
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** One cached row: the raw JSON last seen, and when it was cached (for the "as of HH:mm"
     * label a fallback-rendering screen shows so staff know they're looking at stale data). */
    public record CachedPayload(String payloadJson, String cachedAt) {
    }
}
