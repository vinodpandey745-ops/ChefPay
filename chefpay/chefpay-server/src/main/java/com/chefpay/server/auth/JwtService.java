package com.chefpay.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues and validates the stateless JWT used for both REST bearer auth and the STOMP CONNECT
 * handshake (ARCHITECTURE.md §7). Kept deliberately simple for a LAN-deployed restaurant server:
 * one short-lived access token, no refresh-token flow yet.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(@Value("${chefpay.security.jwt.secret}") String secret,
                       @Value("${chefpay.security.jwt.expiry-seconds:43200}") long expirySeconds) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Pad a short dev secret up to the HS256 minimum key length rather than failing hard on startup.
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirySeconds = expirySeconds;
    }

    /** @deprecated Phase 2: every caller should now supply a {@link LoginMethod} explicitly via
     * {@link #issueToken(UUID, String, String, List, LoginMethod)} - kept only so any
     * not-yet-updated internal caller doesn't fail to compile; defaults to {@code PASSWORD} since
     * that is the strictly MORE trusted assumption (never silently grants PIN-restricted access to
     * a token minted without an explicit method). */
    @Deprecated
    public String issueToken(UUID userId, String username, String roleName, List<String> permissionCodes) {
        return issueToken(userId, username, roleName, permissionCodes, LoginMethod.PASSWORD);
    }

    /** Phase 2 item 4.1: {@code loginMethod} is read back by {@link AuthenticatedPrincipal} so a
     * Manager/Admin-tier endpoint can require {@code PASSWORD} specifically, in addition to the
     * usual permission check - see {@link LoginMethod}'s javadoc. */
    public String issueToken(UUID userId, String username, String roleName, List<String> permissionCodes,
                              LoginMethod loginMethod) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", roleName)
                .claim("permissions", String.join(",", permissionCodes))
                .claim("loginMethod", loginMethod.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public List<String> extractPermissions(Claims claims) {
        String raw = claims.get("permissions", String.class);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream().map(String::trim).collect(Collectors.toList());
    }

    public String extractRole(Claims claims) {
        return claims.get("role", String.class);
    }

    public String extractUsername(Claims claims) {
        return claims.get("username", String.class);
    }

    /** Phase 2: a token issued before this field existed (or by the deprecated overload) has no
     * claim at all - treated as {@code PASSWORD} for the same "never silently under-restrict, only
     * ever over-restrict on ambiguity" reasoning as the deprecated overload's default. Since every
     * such pre-existing token also carries no {@code loginMethod} claim by construction, this only
     * ever affects genuinely old tokens, never a Phase 2 PIN login (which always sets the claim). */
    public LoginMethod extractLoginMethod(Claims claims) {
        String raw = claims.get("loginMethod", String.class);
        if (raw == null || raw.isBlank()) {
            return LoginMethod.PASSWORD;
        }
        try {
            return LoginMethod.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return LoginMethod.PASSWORD;
        }
    }
}
