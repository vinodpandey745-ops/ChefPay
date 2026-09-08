package com.chefpay.server.reports;

import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.subscription.RequiresFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Round 14 (F2.4) - Menu Engineering Matrix, same {@code REPORT_VIEW} audience as every other
 * report. Bistrodesk Phase 4 (requirement #24): gated on {@code ADVANCED_REPORTS} - a distinct,
 * deeper analytical report beyond the basic sales/dashboard numbers every plan includes. See
 * {@link RequiresFeature}'s javadoc. */
@RestController
@RequestMapping("/api/reports/menu-engineering")
@RequiredArgsConstructor
@RequiresFeature("ADVANCED_REPORTS")
public class MenuEngineeringController {

    private final MenuEngineeringService menuEngineeringService;

    @GetMapping
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ApiResponse<MenuEngineeringDtos.MatrixResponseDto> matrix(@RequestParam(required = false) LocalDate from,
                                                                      @RequestParam(required = false) LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);
        return ApiResponse.ok(menuEngineeringService.getMatrix(effectiveFrom, effectiveTo));
    }
}
