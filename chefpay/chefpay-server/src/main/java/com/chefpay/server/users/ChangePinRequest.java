package com.chefpay.server.users;

import jakarta.validation.constraints.NotBlank;

/** Item 9's "Change PIN" as a distinct action from editing anything else about a user - a manager
 * resetting a forgotten PIN doesn't touch the user's display name/role/active state. No version
 * field: unlike most Phase 2 update requests, changing a PIN never conflicts with another field
 * being edited concurrently (see {@code UserAccountService#changePin}, which already predates
 * Phase 2 and doesn't optimistic-lock either). */
public record ChangePinRequest(@NotBlank String newPin) {
}
