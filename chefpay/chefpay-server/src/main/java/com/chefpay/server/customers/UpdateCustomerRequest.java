package com.chefpay.server.customers;

/** Any null field (except version) leaves that attribute unchanged. */
public record UpdateCustomerRequest(
        String name,
        String phone,
        String email,
        String notes,
        long version
) {
}
