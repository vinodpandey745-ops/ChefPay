package com.chefpay.server.config;

import com.chefpay.server.auth.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT security. Session creation is disabled - every request/STOMP-connect carries its
 * own bearer token, matching the multi-terminal architecture where any number of devices can be
 * concurrently authenticated (ARCHITECTURE.md §7).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login").permitAll()
                        // Phase 2: the POS client's first-run flow (branch code -> terminal select
                        // -> user code + PIN) happens entirely BEFORE a JWT exists, same as login
                        // itself - see BranchController#byCode/#listTerminals's javadoc for why each
                        // is safe to expose unauthenticated (branch-code lookup returns nothing
                        // sensitive; the terminal list is scoped to a branch the caller already
                        // proved they know the code for).
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/branches/by-code/*").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/branches/default").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/branches/*/terminals").permitAll()
                        // Phase 2: the platform-owner endpoint is gated by its own separate secret
                        // (checked inside PlatformOwnerController itself, not a JWT/AppUser at all) -
                        // see that controller's javadoc and PHASE2_ORG_SUBSCRIPTION_DESIGN.md Section
                        // A's conflict-resolution #1 for why this is deliberately outside the normal
                        // authentication system rather than a permission any AppUser could hold.
                        .requestMatchers("/platform/**").permitAll()
                        // Bistrodesk Phase 9 (requirement #19-22): a real WhatsApp Business API
                        // provider's inbound webhook has no Bistrodesk JWT to present - same
                        // "gated by its own separate secret, checked inside the controller itself"
                        // shape as /platform/** immediately above, not a JWT/AppUser at all.
                        .requestMatchers("/webhooks/**").permitAll()
                        // Follow-up enhancement ("Enable Logging"): chefpay-web's new client-side
                        // error/warn reporting (lib/logger.ts) must work even before login (a
                        // crash on the login screen itself is exactly the case worth capturing) -
                        // see ClientLogController's own javadoc for why this is safe to leave open
                        // (write-only, no sensitive fields, no query API).
                        .requestMatchers("/api/client-logs").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        // F4.3 Mobile Manager Companion: a static SPA (HTML/CSS/JS) has to be
                        // reachable BEFORE the user has a JWT at all - it authenticates itself via
                        // /api/auth/login like any other client, then every /api/** call it makes
                        // after that still goes through the normal Bearer-token check below. Only
                        // the static assets themselves are permitAll, never any /api/** path.
                        .requestMatchers("/manager", "/manager/**").permitAll()
                        // Bistrodesk Web (chefpay-web, UI modernization Phase 1 - backend
                        // untouched). Same reasoning as /manager above: the compiled SPA bundle
                        // must be reachable pre-login so it can render its own login screen and
                        // call /api/auth/login itself; every subsequent /api/** call it makes
                        // still goes through the normal Bearer-token check below.
                        //
                        // Bistrodesk Phase 12 (URL restructuring, requirement #26): this app now
                        // serves from the server ROOT instead of /app/, so - UNLIKE every other
                        // entry in this list - it can no longer use one prefixed pattern
                        // (/app, /app/**) to cover both its static assets and its client-side
                        // routes. A root-level /** here would also permitAll every /api/** request,
                        // completely defeating this filter chain (authorizeHttpRequests matches in
                        // registration order - the first matching rule wins, so a /** entry
                        // anywhere before .anyRequest().authenticated() below would make that final
                        // rule unreachable). So this is deliberately the exhaustive list of the
                        // SPA's own real static asset paths plus every client-side route
                        // AppController forwards - add a line here (matching AppController's own
                        // list) whenever a new top-level route is added to
                        // chefpay-web/src/App.tsx, exactly as that controller's own javadoc asks.
                        //
                        // Build/run verification (post-Phase-12) found the actual bug this exhaustive
                        // list was missing: every route above resolves via AppController's
                        // "forward:/index.html" - and Spring Security's AuthorizationFilter defaults
                        // shouldFilterAllDispatcherTypes to true (Spring Security 5.8+), so that
                        // internal FORWARD dispatch to /index.html is re-evaluated against this same
                        // rule set as if it were its own incoming request. /index.html itself was
                        // never listed, so every one of these routes fell through to
                        // .anyRequest().authenticated() on the forward and 403'd - even though the
                        // route's own literal path matched permitAll on the initial dispatch. Static
                        // assets (manifest.json, /assets/**, ...) never hit this because they're
                        // served directly with no forward; /manager and /admin never hit it because
                        // their permitAll patterns are wildcards ("/manager/**", "/admin/**") that
                        // already cover their own forward targets.
                        .requestMatchers(
                                "/assets/**",
                                "/manifest.json", "/favicon.svg", "/apple-touch-icon.png", "/sw.js",
                                "/icon-192.png", "/icon-512.png", "/icon-maskable-192.png", "/icon-maskable-512.png",
                                "/index.html",
                                "/", "/login", "/staff-login", "/staff-login/login",
                                "/dashboard", "/pos", "/kitchen", "/tables", "/orders",
                                "/menu", "/inventory", "/purchase-orders", "/reports", "/customers",
                                "/reservations", "/users", "/settings", "/branches-terminals",
                                "/organization", "/subscription"
                        ).permitAll()
                        // Bistrodesk Phase 12: old /app/* bookmarks/QR codes/receipts redirect to
                        // their new root-level equivalent (LegacyAppRedirectController) - that
                        // redirect endpoint itself has to be reachable pre-login same as everything
                        // else above, and /app/** no longer serves any real static asset (the old
                        // static/app/ build output was removed as part of this same migration), so
                        // an /app/** pattern here is safe the same way it always was.
                        .requestMatchers("/app", "/app/**").permitAll()
                        // Bistrodesk Phase 4 (requirement #24's Admin UI): the platform-owner Admin
                        // UI is a static SPA that has to be reachable before any credential exists,
                        // same reasoning as /manager and /app above - it never authenticates via a
                        // JWT at all, only against /platform/** (already permitAll above) using the
                        // X-Platform-Owner-Key header, checked inside PlatformOwnerController itself.
                        .requestMatchers("/admin", "/admin/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Permissive CORS for LAN tablet/mobile/web clients (Phase 2+). Tighten per-deployment via config once real origins are known. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
