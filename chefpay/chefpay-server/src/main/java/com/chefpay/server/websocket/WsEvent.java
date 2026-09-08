package com.chefpay.server.websocket;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Generic envelope for every WebSocket event (ARCHITECTURE.md §6). Clients that only care
 * "something about entity X changed" can react to {@code eventType}/{@code entityId} alone and
 * re-fetch via REST, so payload evolution in {@code data} never breaks a client that ignores it.
 */
public record WsEvent(
        String eventType,
        UUID entityId,
        long version,
        LocalDateTime timestamp,
        String correlationId,
        Map<String, Object> data
) {
    public static WsEvent of(String eventType, UUID entityId, long version, String correlationId, Map<String, Object> data) {
        return new WsEvent(eventType, entityId, version, LocalDateTime.now(), correlationId, data);
    }
}
