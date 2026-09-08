package com.chefpay.server.users;

import jakarta.validation.constraints.NotBlank;

/** Phase 2: {@code userCode} is optional - blank/null auto-generates one from the role name (see
 * {@code UserAccountService#generateUserCode}), same "auto-generate OR type your own" posture item
 * 9 asks for, applied to the user code exactly like it already applies to the PIN. */
public record CreateUserRequest(
        @NotBlank String username,
        @NotBlank String displayName,
        @NotBlank String password,
        String pin,
        @NotBlank String role,
        String userCode
) {
}
