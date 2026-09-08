package com.chefpay.server.fraud;

import com.chefpay.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Round 14 (F3.4) - same {@code RULE_CONFIG_MANAGE} audience as the Round 13 threshold screen this
 * report is meant to inform. */
@RestController
@RequestMapping("/api/fraud/rule-precision")
@RequiredArgsConstructor
public class RulePrecisionController {

    private final RulePrecisionService rulePrecisionService;

    @GetMapping
    @PreAuthorize("hasAuthority('RULE_CONFIG_MANAGE')")
    public ApiResponse<RulePrecisionDtos.RulePrecisionReportDto> report(@RequestParam(required = false) LocalDate from,
                                                                         @RequestParam(required = false) LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);
        return ApiResponse.ok(rulePrecisionService.getReport(effectiveFrom, effectiveTo));
    }
}
