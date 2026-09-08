package com.chefpay.server.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/** Round 10's Feature C (smart reorder drafts) and Feature D (audit anomaly flagging) DTOs, used by
 * {@link AiOpsController}, share this class with Round 14's F4.1 "NL Ops Assistant" write-command
 * DTOs, used by {@link AiOpsAssistantController} - both controllers live under the same
 * {@code /api/ai/ops} base path and package, so they were consolidated into one DTOs holder rather
 * than two near-identically-named classes. */
public final class AiOpsDtos {

    private AiOpsDtos() {
    }

    // ---- Round 10 Feature C: smart reorder drafts ----

    public record ReorderDraftResponse(String draftMessage, int itemCount) {
    }

    // ---- Round 10 Feature D: audit anomaly flagging ----

    public record AnomalyScanRequest(LocalDate from, LocalDate to) {
    }

    public record AnomalyScanResponse(String summary, int entriesScanned, LocalDate from, LocalDate to) {
    }

    // ---- Round 14 (F4.1): NL Ops Assistant bounded write commands ----

    public record InterpretCommandRequest(@NotBlank String instruction) {
    }

    /** {@code action} is one of the small allow-listed set {@code AiOpsAssistantService} supports
     * ({@code TOGGLE_ITEM_AVAILABILITY} today) or {@code UNRECOGNIZED} when the instruction couldn't
     * be confidently mapped to one - {@code executable} is false in every case except a fully
     * resolved, unambiguous write command, so the UI knows whether to offer a Confirm button at all. */
    public record InterpretedCommandDto(String action, boolean executable, UUID menuItemId, String menuItemName,
                                         Boolean currentAvailable, Boolean proposedAvailable, String summary) {
    }

    public record ExecuteToggleAvailabilityRequest(@NotNull UUID menuItemId, @NotNull Boolean available, long expectedVersion) {
    }

    public record ExecutedCommandDto(boolean success, String message) {
    }
}
