package com.chefpay.server.websocket;

import com.chefpay.server.common.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Single publish path used by every module's service layer to broadcast a domain event to the
 * relevant {@code /topic/*} channel (ARCHITECTURE.md §6/§21). Centralizing this keeps the event
 * envelope shape (see {@link WsEvent}) consistent no matter which module publishes.
 */
@Service
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publish(String topic, String eventType, UUID entityId, long version, Map<String, Object> data) {
        WsEvent event = WsEvent.of(eventType, entityId, version, CorrelationIdHolder.get(), data);
        messagingTemplate.convertAndSend(topic, event);
    }
}
