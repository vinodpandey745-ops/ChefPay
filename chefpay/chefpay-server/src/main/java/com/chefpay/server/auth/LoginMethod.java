package com.chefpay.server.auth;

/**
 * Phase 2 item 4.1: which credential a session's JWT was actually issued from. Carried as a JWT
 * claim (see {@link JwtService#issueToken}) and read back by {@link AuthenticatedPrincipal} so any
 * Manager/Admin-tier endpoint can require {@code PASSWORD} specifically - enforced at the API, not
 * just by which screen the client shows, so a PIN-authenticated session can never reach
 * organization/branch/terminal/subscription administration even if some future bug over-grants a
 * permission to a role that shouldn't have it. Defense in depth on top of (not instead of) the
 * existing {@code @PreAuthorize} permission checks.
 */
public enum LoginMethod {
    PASSWORD,
    PIN
}
