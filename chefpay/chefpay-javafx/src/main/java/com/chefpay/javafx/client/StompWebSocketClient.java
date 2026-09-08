package com.chefpay.javafx.client;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Real-time transport foundation (ARCHITECTURE.md §6). Connects to chefpay-server's STOMP
 * endpoint, authenticates the CONNECT frame with the session's JWT, and dispatches inbound
 * MESSAGE frames to per-topic listeners on the JavaFX application thread. Reconnects with a
 * capped backoff on drop, matching the "don't silently lose the live connection" spirit of
 * requirement §39 - full offline transaction queuing is a Phase 6 concern, but a client that
 * quietly stays disconnected forever would defeat the entire real-time architecture, so
 * reconnect is part of the Phase 1 foundation.
 */
public class StompWebSocketClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chefpay-ws-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, List<BiConsumer<String, String>>> listenersByTopic = new ConcurrentHashMap<>();
    private final AtomicInteger subscriptionCounter = new AtomicInteger();
    /** Round 27 (pre-deployment audit) fix: this class's own javadoc has always claimed "reconnects
     * with a capped backoff," but {@link #scheduleReconnect()} was actually a flat, fixed 5-second
     * retry - not a bug exactly (bounded, not a busy-spin), but a real doc/code mismatch found while
     * auditing. Implemented the backoff the javadoc already promised rather than watering the
     * javadoc down to match a flat retry: 5s, 10s, 20s, capped at 30s, reset back to the first step
     * once a CONNECTED frame is actually received (see the "CONNECTED" case in {@link
     * #handleFrame(String)}) - a raw TCP connect succeeding but the STOMP handshake itself failing
     * does not count as recovered. */
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final ObjectProperty<ConnectionStatus> status = new SimpleObjectProperty<>(ConnectionStatus.OFFLINE);

    private volatile WebSocket webSocket;
    private volatile boolean shouldRun = false;
    private StringBuilder inboundBuffer = new StringBuilder();

    public ObjectProperty<ConnectionStatus> statusProperty() {
        return status;
    }

    public void start() {
        shouldRun = true;
        connect();
    }

    public void stop() {
        shouldRun = false;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client shutdown");
        }
        scheduler.shutdownNow();
    }

    /** Subscribe to a topic; the listener receives (destination, rawJsonBody) on the JavaFX thread. */
    public void subscribe(String destination, BiConsumer<String, String> onMessage) {
        listenersByTopic.computeIfAbsent(destination, d -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(onMessage);
        if (webSocket != null && status.get() == ConnectionStatus.ONLINE) {
            sendSubscribe(destination);
        }
    }

    private void connect() {
        if (!shouldRun) {
            return;
        }
        setStatus(ConnectionStatus.CONNECTING);
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(ServerConfig.wsUrl()), new Listener())
                .whenComplete((ws, ex) -> {
                    if (ex != null) {
                        setStatus(ConnectionStatus.OFFLINE);
                        scheduleReconnect();
                        return;
                    }
                    this.webSocket = ws;
                    ws.sendText(StompFrame.connect(SessionStore.get().getToken()).encode(), true);
                });
    }

    private void scheduleReconnect() {
        if (!shouldRun) {
            return;
        }
        int attempt = reconnectAttempt.getAndIncrement();
        long delaySeconds = Math.min(5L << Math.min(attempt, 20), 30L); // 5, 10, 20, 30, 30, 30, ...
        scheduler.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

    private void setStatus(ConnectionStatus newStatus) {
        Platform.runLater(() -> status.set(newStatus));
    }

    private void sendSubscribe(String destination) {
        String id = "sub-" + subscriptionCounter.incrementAndGet();
        webSocket.sendText(StompFrame.subscribe(id, destination).encode(), true);
    }

    private class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            inboundBuffer.append(data);
            webSocket.request(1);
            if (last) {
                String raw = inboundBuffer.toString();
                inboundBuffer = new StringBuilder();
                handleFrame(raw);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            setStatus(ConnectionStatus.OFFLINE);
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            setStatus(ConnectionStatus.OFFLINE);
            scheduleReconnect();
        }
    }

    private void handleFrame(String raw) {
        if (raw.isBlank()) {
            return; // heartbeat newline
        }
        StompFrame frame = StompFrame.decode(raw);
        switch (frame.command) {
            case "CONNECTED" -> {
                reconnectAttempt.set(0);
                setStatus(ConnectionStatus.ONLINE);
                listenersByTopic.keySet().forEach(this::sendSubscribe);
            }
            case "MESSAGE" -> {
                String destination = frame.headers.get("destination");
                List<BiConsumer<String, String>> listeners = listenersByTopic.get(destination);
                if (listeners != null) {
                    // Round 27 (pre-deployment audit) fix: forEach used to abort the moment any one
                    // listener threw, silently skipping every remaining listener for this message -
                    // a single misbehaving screen could starve every other subscriber to the same
                    // topic. Each listener now runs independently; a failing one no longer takes the
                    // others down with it. (This module has no logger anywhere - see the audit
                    // notes - so this still only surfaces via stderr, same visibility as before;
                    // what's fixed here is the OTHER listeners no longer being silently skipped.)
                    Platform.runLater(() -> listeners.forEach(l -> {
                        try {
                            l.accept(destination, frame.body);
                        } catch (RuntimeException listenerFailure) {
                            listenerFailure.printStackTrace();
                        }
                    }));
                }
            }
            case "ERROR" -> setStatus(ConnectionStatus.OFFLINE);
            default -> { /* RECEIPT etc. - nothing to do yet */ }
        }
    }
}
