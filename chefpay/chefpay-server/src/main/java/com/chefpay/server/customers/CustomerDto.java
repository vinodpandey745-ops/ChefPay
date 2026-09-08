package com.chefpay.server.customers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CustomerDto(
        UUID id,
        String name,
        String phone,
        String email,
        String notes,
        int visitCount,
        BigDecimal totalSpend,
        LocalDateTime lastVisitAt,
        long version,
        UUID branchId,
        String branchName
) {
}
