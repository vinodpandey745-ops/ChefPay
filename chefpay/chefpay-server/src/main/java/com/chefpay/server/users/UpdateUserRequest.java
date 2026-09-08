package com.chefpay.server.users;

/** Any null field is left unchanged. version must match the current row (optimistic locking). */
public record UpdateUserRequest(
        String displayName,
        String role,
        Boolean active,
        String newPassword,
        String newPin,
        long version
) {
}
