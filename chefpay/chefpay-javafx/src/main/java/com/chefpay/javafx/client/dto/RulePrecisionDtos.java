package com.chefpay.javafx.client.dto;

import java.time.LocalDate;
import java.util.List;

/** Mirrors {@code com.chefpay.server.fraud.RulePrecisionDtos} - Round 14 (F3.4). */
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
