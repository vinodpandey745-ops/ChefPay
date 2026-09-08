package com.chefpay.server.purchasing;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record SupplierDto(UUID id, String name, String contactPerson, String phone, String email,
                               String address, String notes, boolean active, long version,
                               UUID branchId, String branchName) {
    }

    /** Bistrodesk branch-isolation release (requirement #6): {@code branchId} is optional the same
     * way {@code CreateCustomerRequest#branchId} is - resolved to a real branch server-side
     * ({@code SupplierController#create}), never left null. */
    public record CreateSupplierRequest(@NotBlank String name, String contactPerson, String phone, String email,
                                         String address, String notes, UUID branchId) {
    }

    /** Null = unchanged, same convention as every other update request in this app. */
    public record UpdateSupplierRequest(String name, String contactPerson, String phone, String email,
                                         String address, String notes, Boolean active, long version) {
    }
}
