package com.chefpay.javafx.client;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Round 11: "when POS logs in first time for the day it should sync with server (as per network
 * availability) then store some important information locally... so that if pos is offline it
 * runs lightly." This is the mechanism - a background worker, started once per login
 * ({@link #start}), that:
 * <ol>
 *   <li>Immediately pulls menu, tables, restaurant config, and (Bistrodesk Phase 10) discount
 *       presets into {@link LocalDatabase}'s read-through cache the moment login succeeds (today's
 *       "first login of the day" sync - logging in each morning is exactly when this fires).</li>
 *   <li>Probes {@code /actuator/health} on a timer to track reachability, exposed as
 *       {@link #onlineProperty} for {@code ShellView}'s status chip.</li>
 *   <li>Re-runs the same cache refresh the moment the probe flips from unreachable back to
 *       reachable, so a terminal that went offline mid-shift and came back doesn't wait for the
 *       next login to pick up menu/table changes made elsewhere while it was cut off.</li>
 * </ol>
 *
 * <p>Mirrors RetailPOS's {@code SyncEngine} in shape (singleton, one background
 * {@link ScheduledExecutorService}, bindable JavaFX properties for the UI) but this app's actual
 * gap is different: RetailPOS already has a WRITE outbox with nothing enqueuing into it yet;
 * ChefPay's gap was a READ cache for offline resilience, which is what this class actually
 * implements end to end. {@link LocalDatabase}'s {@code sync_queue} table exists here too, ready
 * for a future write-mutation type, but - exactly as in RetailPOS today - nothing calls {@link
 * LocalDatabase#insertQueueItem} yet; see this class's own javadoc history/PR notes rather than
 * assuming full offline order-taking is safe to rely on yet (a live, shared kitchen display and
 * table state make queuing new orders while offline a bigger, separate design problem than caching
 * menu/table reads - see the delivered write-up for why that's intentionally out of scope this
 * round).
 */
public final class SyncEngine {

    private static final SyncEngine INSTANCE = new SyncEngine();

    public static SyncEngine get() {
        return INSTANCE;
    }

    public static final String CACHE_KEY_MENU = "menu";
    public static final String CACHE_KEY_TABLES = "tables";
    public static final String CACHE_KEY_RESTAURANT = "restaurant";
    /** Bistrodesk Phase 10 addition - discount presets, warmed here for the same reason menu/tables/
     * restaurant already are: {@link com.chefpay.javafx.billing.BillingView#loadDiscountPresets}
     * reads it back on a live-fetch failure, exactly mirroring {@code loadRestaurantConfig}'s own
     * cache-fallback pattern. Tax config needs no separate key - it lives on the already-cached
     * {@code RestaurantDto} fields, not a standalone endpoint. */
    public static final String CACHE_KEY_DISCOUNTS = "discounts";

    /** How often the reachability probe runs. Cheap (a single unauthenticated GET), so this can be
     * more frequent than RetailPOS's push cycle - there's no outbox batch to worry about hammering. */
    private static final long POLL_INTERVAL_SECONDS = 15;

    private final BooleanProperty online = new SimpleBooleanProperty(true);
    private final ObjectProperty<LocalDateTime> lastSyncedAt = new SimpleObjectProperty<>();

    private ScheduledExecutorService executor;
    private ApiClient apiClient;
    private volatile boolean started = false;
    private volatile boolean wasOnline = true;

    private SyncEngine() {
    }

    /** Idempotent - a second call (e.g. a re-login without restarting the app) is a no-op. */
    public synchronized void start(ApiClient apiClient) {
        if (started) {
            return;
        }
        started = true;
        this.apiClient = apiClient;
        LocalDatabase.init();
        executor = Executors.newSingleThreadScheduledExecutor(SyncEngine::newDaemonThread);
        // Today's login-time sync - fires once immediately, network permitting (refreshCache
        // itself no-ops safely if the server can't be reached, leaving whatever was cached from a
        // previous session in place).
        executor.execute(this::refreshCache);
        executor.scheduleWithFixedDelay(this::runCycleSafely, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Stops the background worker - called on logout so a stale session's probe doesn't keep
     * running (and doesn't keep flipping a since-replaced login's status chip) underneath a fresh
     * login's own {@link #start}. */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        started = false;
    }

    private static Thread newDaemonThread(Runnable r) {
        Thread t = new Thread(r, "chefpay-sync-engine");
        t.setDaemon(true);
        return t;
    }

    /** A {@link ScheduledExecutorService} that throws from its task silently stops scheduling
     * future runs forever - never let an unexpected exception escape this method. */
    private void runCycleSafely() {
        try {
            runCycle();
        } catch (Exception unexpected) {
            Platform.runLater(() -> online.set(false));
        }
    }

    private void runCycle() {
        boolean reachable = apiClient.pingHealth();
        Platform.runLater(() -> online.set(reachable));
        if (reachable && !wasOnline) {
            // Just came back from an outage - refresh the cache now rather than waiting for the
            // next login, so this terminal picks up whatever menu/table changes happened elsewhere
            // while it was cut off.
            refreshCache();
        }
        wasOnline = reachable;
    }

    /** Pulls menu, tables, and restaurant config fresh and overwrites {@link LocalDatabase}'s
     * cache for each - safe to call whether or not the server is currently reachable; each fetch
     * is independent and a failure on one (say, menu) doesn't block caching the others. */
    private void refreshCache() {
        boolean anySucceeded = false;
        anySucceeded |= refreshOne(SyncEngine.CACHE_KEY_MENU, "/api/menu");
        anySucceeded |= refreshOne(SyncEngine.CACHE_KEY_TABLES, "/api/tables");
        anySucceeded |= refreshOne(SyncEngine.CACHE_KEY_RESTAURANT, "/api/restaurant");
        // Best-effort: a caller without DISCOUNT_APPROVE/BILLING_MANAGE/RESTAURANT_MANAGE gets an
        // ApiException here (refreshOne swallows it) and simply never warms this key - the same
        // "not every login can populate every cache entry" behavior loadDiscountPresets already
        // tolerates on its own live-fetch path.
        anySucceeded |= refreshOne(SyncEngine.CACHE_KEY_DISCOUNTS, "/api/billing/discounts");
        if (anySucceeded) {
            Platform.runLater(() -> lastSyncedAt.set(LocalDateTime.now()));
        }
    }

    private boolean refreshOne(String cacheKey, String path) {
        try {
            JsonNode data = apiClient.get(path);
            LocalDatabase.putCached(cacheKey, data.toString());
            return true;
        } catch (ApiException ex) {
            return false;
        }
    }

    // ---- Bindable state for ShellView's status chip ----

    public BooleanProperty onlineProperty() {
        return online;
    }

    public ObjectProperty<LocalDateTime> lastSyncedAtProperty() {
        return lastSyncedAt;
    }
}
