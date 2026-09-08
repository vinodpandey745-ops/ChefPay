package com.chefpay.server.auth;

import com.chefpay.server.common.ApiException;

import java.util.UUID;

/** Minimal principal placed in the Spring Security context after a JWT is validated.
 *
 * <p>Phase 2: {@code loginMethod} is the server-side half of item 4.1's "Manager/Admin
 * authentication must be strictly Username + Password, never PIN" - {@link #requirePasswordLogin()}
 * is the one call every new Organization/Branch/Terminal/User/Role/Subscription-management
 * endpoint makes, in addition to its usual {@code @PreAuthorize} permission check. */
public record AuthenticatedPrincipal(UUID userId, String username, LoginMethod loginMethod) {

    public boolean isPasswordLogin() {
        return loginMethod == LoginMethod.PASSWORD;
    }

    /** Throws a clear 403 (never a confusing generic one) the moment a PIN-authenticated session
     * reaches an endpoint that requires password login - see this record's own javadoc. */
    public void requirePasswordLogin() {
        if (!isPasswordLogin()) {
            throw ApiException.forbidden("This action requires signing in with a username and password, "
                    + "not a PIN. Sign in again from the Manager/Admin login screen.");
        }
    }
}
