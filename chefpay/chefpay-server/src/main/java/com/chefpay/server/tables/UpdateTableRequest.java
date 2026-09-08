package com.chefpay.server.tables;

/** Any null field (except version) leaves that attribute unchanged. */
public record UpdateTableRequest(
        String name,
        Integer seatingCapacity,
        String section,
        String status,
        Integer gridRow,
        Integer gridColumn,
        Boolean active,
        long version
) {
}
