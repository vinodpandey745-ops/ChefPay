package com.chefpay.server.ai;

public final class AiNightlySummaryDtos {

    private AiNightlySummaryDtos() {
    }

    public record GenerateNowResponse(String summary) {
    }
}
