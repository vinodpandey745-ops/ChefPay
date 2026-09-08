package com.chefpay.server.fraud;

import java.time.LocalDate;
import java.util.List;

public final class RulePrecisionDtos {

    private RulePrecisionDtos() {
    }

    public record RulePrecisionRowDto(String ruleCode, String ruleName, long totalAnomalies, long confirmedTheft,
                                       long legitimateError, long falsePositive, long unresolved,
                                       double confirmedTheftRatePercent, double falsePositiveRatePercent) {
    }

    public record RulePrecisionReportDto(LocalDate from, LocalDate to, List<RulePrecisionRowDto> rows) {
    }
}
