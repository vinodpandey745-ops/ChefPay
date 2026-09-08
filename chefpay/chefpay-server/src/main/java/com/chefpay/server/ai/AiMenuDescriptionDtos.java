package com.chefpay.server.ai;

public final class AiMenuDescriptionDtos {

    private AiMenuDescriptionDtos() {
    }

    public record SuggestDescriptionResponse(String suggestedDescription) {
    }
}
