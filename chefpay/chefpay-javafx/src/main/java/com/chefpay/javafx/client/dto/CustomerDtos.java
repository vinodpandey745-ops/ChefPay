package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.customers.*} - backs the Customers directory screen and the
 * Delivery/Pickup quick-order dialog's phone lookup on {@code TableMatrixView}. */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CustomerDto(UUID id, String name, String phone, String email, String notes,
                               int visitCount, BigDecimal totalSpend, LocalDateTime lastVisitAt, long version) {
    }

    public record CreateCustomerRequest(String name, String phone, String email, String notes) {
    }

    /** Any null field (except version) leaves that attribute unchanged - mirrors the server DTO. */
    public record UpdateCustomerRequest(String name, String phone, String email, String notes, long version) {
    }
}
