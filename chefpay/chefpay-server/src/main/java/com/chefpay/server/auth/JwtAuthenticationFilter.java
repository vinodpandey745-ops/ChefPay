package com.chefpay.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Reads {@code Authorization: Bearer <jwt>}, validates it, and populates the Spring Security context. */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                List<GrantedAuthority> authorities = new java.util.ArrayList<>();
                jwtService.extractPermissions(claims).forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
                authorities.add(new SimpleGrantedAuthority("ROLE_" + jwtService.extractRole(claims)));

                var authToken = new UsernamePasswordAuthenticationToken(
                        new AuthenticatedPrincipal(jwtService.extractUserId(claims), jwtService.extractUsername(claims),
                                jwtService.extractLoginMethod(claims)),
                        null,
                        authorities
                );
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
