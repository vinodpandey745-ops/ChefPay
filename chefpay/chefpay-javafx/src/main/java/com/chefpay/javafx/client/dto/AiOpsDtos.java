package com.chefpay.javafx.client.dto;

import java.util.UUID;

/** Mirrors {@code com.chefpay.server.ai.AiOpsDtos} - Round 14 (F4.1). */
public final class AiOpsDtos {

    private AiOpsDtos() {
    }

    public record InterpretCommandRequest(String instruction) {
    }

    public record InterpretedCommandDto(String action, boolean executable, UUID menuItemId, String menuItemName,
                                         Boolean currentAvailable, Boolean proposedAvailable, String summary) {
    }

    public record ExecuteToggleAvailabilityRequest(UUID menuItemId, Boolean available, long expectedVersion) {
    }

    public record ExecutedCommandDto(boolean success, String message) {
    }
}
