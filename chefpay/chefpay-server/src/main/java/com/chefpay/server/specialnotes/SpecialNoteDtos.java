package com.chefpay.server.specialnotes;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class SpecialNoteDtos {

    private SpecialNoteDtos() {
    }

    public record SpecialNoteDto(UUID id, UUID branchId, String text, int displayOrder, boolean active, long version) {
    }

    public record CreateSpecialNoteRequest(UUID branchId, @NotBlank String text, int displayOrder) {
    }

    /** Any null field (except version) leaves that attribute unchanged. */
    public record UpdateSpecialNoteRequest(String text, Integer displayOrder, Boolean active, long version) {
    }
}
