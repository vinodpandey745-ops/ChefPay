package com.chefpay.server.ai;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public final class AiInsightsDtos {

    private AiInsightsDtos() {
    }

    /** {@code from}/{@code to} default to the trailing 30 days when omitted - wide enough for a
     * useful conversation ("how did last week compare to the week before") without the client
     * having to know that default itself. */
    public record ChatRequest(@NotBlank String question, LocalDate from, LocalDate to) {
    }

    public record ChatResponse(String answer, LocalDate from, LocalDate to) {
    }
}
