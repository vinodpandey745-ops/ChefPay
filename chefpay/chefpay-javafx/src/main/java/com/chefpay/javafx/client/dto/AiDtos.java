package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Client mirrors of every Round 10 AI feature's server DTOs (see {@code com.chefpay.server.ai.*}). */
public final class AiDtos {

    private AiDtos() {
    }

    // ---- Feature A: AI Menu Setup from Photo/PDF ----

    public record AnalyzeRequest(String imageBase64, String mimeType) {
    }

    public record DraftItemDto(String name, String categoryName, BigDecimal price, String foodType, String description) {
    }

    public record AnalyzeResponse(List<DraftItemDto> items) {
    }

    public record ApplyItemDto(String name, String categoryName, BigDecimal price, String foodType, String description) {
    }

    public record ApplyRequest(List<ApplyItemDto> items) {
    }

    public record ApplyResponse(int itemsCreated, int categoriesCreated) {
    }

    // ---- Feature B: "Ask Your Data" ----

    public record ChatRequest(String question, LocalDate from, LocalDate to) {
    }

    public record ChatResponse(String answer, LocalDate from, LocalDate to) {
    }

    // ---- Feature C: Smart reorder drafts ----

    public record ReorderDraftResponse(String draftMessage, int itemCount) {
    }

    // ---- Feature D: Audit anomaly flagging ----

    public record AnomalyScanRequest(LocalDate from, LocalDate to) {
    }

    public record AnomalyScanResponse(String summary, int entriesScanned, LocalDate from, LocalDate to) {
    }

    // ---- Feature E: AI menu item descriptions ----

    public record SuggestDescriptionResponse(String suggestedDescription) {
    }

    // ---- Feature F: Nightly AI summary ----

    public record GenerateNowResponse(String summary) {
    }
}
