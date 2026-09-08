package com.chefpay.server.customers;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** Bistrodesk branch-isolation release (requirement #6): {@code branchId} is optional here the same
 * way {@code InventoryDtos.CreateItemRequest#branchId} is - a single-branch caller or one with a
 * default branch never needs to pass it; a multi-branch/unrestricted caller (see
 * {@code CustomersPage.tsx}'s branch picker, shown only in that case) can pick one explicitly. It is
 * still resolved to a real branch id server-side (never left null - see {@code Customer#branch}'s
 * javadoc on why this entity, unlike inventory, has no legitimate "unassigned" resting state). */
public record CreateCustomerRequest(
        @NotBlank String name,
        String phone,
        String email,
        String notes,
        UUID branchId
) {
}
