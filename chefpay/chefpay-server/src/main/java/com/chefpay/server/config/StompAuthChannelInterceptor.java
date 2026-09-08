package com.chefpay.server.config;

import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.auth.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP CONNECT frame using the same JWT bearer token REST calls use, so a
 * WebSocket subscription is tied to a real logged-in user rather than being anonymous.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try {
                    Claims claims = jwtService.parse(authHeader.substring(7));
                    var principal = new AuthenticatedPrincipal(jwtService.extractUserId(claims), jwtService.extractUsername(claims),
                            jwtService.extractLoginMethod(claims));
                    accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
                } catch (JwtException | IllegalArgumentException ignored) {
                    // Leave unauthenticated; endpoints/topics that require identity will reject downstream.
                }
            }
        }
        return message;
    }
}
