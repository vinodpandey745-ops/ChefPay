package com.chefpay.javafx.client.dto;

import java.util.UUID;

public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record SupplierDto(UUID id, String name, String contactPerson, String phone, String email,
                               String address, String notes, boolean active, long version) {
    }

    public record CreateSupplierRequest(String name, String contactPerson, String phone, String email,
                                         String address, String notes) {
    }

    public record UpdateSupplierRequest(String name, String contactPerson, String phone, String email,
                                         String address, String notes, Boolean active, long version) {
    }
}
