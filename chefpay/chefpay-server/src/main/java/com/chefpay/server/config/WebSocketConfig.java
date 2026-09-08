package com.chefpay.server.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket foundation (ARCHITECTURE.md §6). {@code /ws} is the single connection
 * endpoint every client (JavaFX desktop, tablet/mobile web, kitchen display) speaks native STOMP
 * over WebSocket to - all target clients are modern (JavaFX WebSocket client, evergreen
 * browsers), so no SockJS long-polling fallback is needed for this LAN deployment. The broker
 * fans out to the topic namespaces listed in the architecture doc as each owning module lands.
 * Phase 1 only wires the transport and the {@code /topic/notifications} channel used for
 * {@code USER_LOGGED_IN}.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
