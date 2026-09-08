package com.chefpay.javafx.client.dto;

import java.util.UUID;

/** Mirrors {@code com.chefpay.server.specialnotes.SpecialNoteDtos} (Round 8 Special Note Management
 * screen / order-taking quick-pick presets for {@code OrderItem#getSpecialInstructions()}). */
public final class SpecialNoteDtos {

    private SpecialNoteDtos() {
    }

    public record SpecialNoteDto(UUID id, UUID branchId, String text, int displayOrder, boolean active, long version) {
    }

    public record CreateSpecialNoteRequest(UUID branchId, String text, int displayOrder) {
    }

    /** Any null field (except version) leaves that attribute unchanged. */
    public record UpdateSpecialNoteRequest(String text, Integer displayOrder, Boolean active, long version) {
    }
}
