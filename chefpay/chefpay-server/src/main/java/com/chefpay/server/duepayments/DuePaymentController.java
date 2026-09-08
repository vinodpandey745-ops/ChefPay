package com.chefpay.server.duepayments;

import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.duepayments.DuePaymentDtos.DuePaymentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Due Payment Management (requirement Round 8) - bills that are out but not fully settled. */
@RestController
@RequestMapping("/api/due-payments")
@RequiredArgsConstructor
public class DuePaymentController {

    private final DuePaymentService duePaymentService;

    @GetMapping
    @PreAuthorize("hasAuthority('BILLING_MANAGE') or hasAuthority('MANAGER') or hasAuthority('REPORT_VIEW')")
    public ApiResponse<List<DuePaymentDto>> list() {
        return ApiResponse.ok(duePaymentService.listDuePayments());
    }
}
