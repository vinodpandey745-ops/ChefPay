package com.chefpay.server.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class AiMenuImportDtos {

    private AiMenuImportDtos() {
    }

    public record AnalyzeRequest(@NotBlank String imageBase64, String mimeType) {
    }

    /** One item as read off the photo/PDF page - a PREVIEW only, nothing is saved to the menu until
     * the reviewed list comes back through {@link ApplyRequest}. {@code price} is nullable because
     * a smudged/illegible photo may not always yield a confident number - the client shows those
     * blank for the owner to fill in rather than silently guessing zero. */
    public record DraftItemDto(String name, String categoryName, BigDecimal price, String foodType, String description) {
    }

    public record AnalyzeResponse(List<DraftItemDto> items) {
    }

    /** Round 18: {@code categoryId}, when supplied, is used verbatim as this item's category and
     * {@code categoryName} is ignored for matching purposes (kept only for display/fallback) -
     * this is how the client's new "map each draft category to an existing one, or create new"
     * review step guarantees no accidental near-duplicate category (e.g. "Beverage" next to an
     * existing "Beverages") ever gets created again. When {@code categoryId} is null (an older
     * client, or a group the reviewer explicitly chose "create new" for), {@link
     * AiMenuImportController#apply} falls back to the original find-by-name-or-create behavior. */
    public record ApplyItemDto(@NotBlank String name, @NotBlank String categoryName, UUID categoryId,
                                @NotNull BigDecimal price, String foodType, String description) {
    }

    /** {@code branchId} (Bistrodesk branch-isolation release, requirement #1) is optional here only
     * because {@link AiMenuImportController#apply} resolves this caller's own default/sole branch
     * when omitted - a multi-branch/unrestricted caller with no default MUST supply one or the
     * request is rejected (BRANCH_REQUIRED), same as {@code MenuController#createItem}. One import
     * batch always lands on exactly one branch (an owner photographing their menu is setting up one
     * location at a time), never a shared/centralized item - see {@code MenuItem#branch}'s javadoc. */
    public record ApplyRequest(@NotEmpty List<ApplyItemDto> items, UUID branchId) {
    }

    public record ApplyResponse(int itemsCreated, int categoriesCreated) {
    }
}
