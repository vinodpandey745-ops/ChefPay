package com.chefpay.server.menu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class MenuDtos {

    private MenuDtos() {
    }

    /** Round 18: {@code parentCategoryId}/{@code parentCategoryName} are null for a normal
     * top-level category (every pre-Round-18 category) - non-null marks this as a subcategory
     * (e.g. "Veg" under "Main Course"), rendered nested under its parent by the Menu Editor. */
    public record CategoryDto(UUID id, String name, int displayOrder, boolean active, long version, List<ItemDto> items,
                               UUID parentCategoryId, String parentCategoryName) {
    }

    public record ItemDto(UUID id, UUID categoryId, String name, String sku, String plu, String description,
                           BigDecimal price, String taxCode, UUID stationId, String stationName, boolean vegetarian,
                           String foodType, boolean available, boolean active, boolean directSale, BigDecimal halfPrice,
                           String barcode, Integer prepTimeMinutes, long version,
                           // Bistrodesk branch-isolation release (requirement #1): every item now always belongs
                           // to a branch - null here means a legacy, not-yet-backfilled row (see MenuController's
                           // javadoc), which should not occur once the server-side branch backfill has run.
                           UUID branchId, String branchName) {
    }

    /** Round 18: {@code parentCategoryId} (optional) creates this category directly as a
     * subcategory - e.g. creating "Veg" with {@code parentCategoryId} pointing at "Main Course". */
    public record CreateCategoryRequest(@NotBlank String name, int displayOrder, UUID parentCategoryId) {
    }

    /**
     * Any null field (except version/clearParentCategory) leaves that attribute unchanged, same
     * convention as {@link UpdateItemRequest}. {@code active:false} still works exactly as before
     * (soft-delete) for a category you want to keep history for; Round 18 additionally adds a real
     * {@code DELETE}/merge path (see {@code MenuController}) for a category that's genuinely a
     * duplicate with nothing worth keeping, or should be reorganized under another category.
     * {@code parentCategoryId} sets/changes this category's parent (making it a subcategory);
     * {@code clearParentCategory=true} removes it, restoring this to a top-level category - same
     * "a plain null can't distinguish leave-alone from clear" pattern {@code UpdateItemRequest
     * #clearStation} already uses.
     */
    public record UpdateCategoryRequest(String name, Integer displayOrder, Boolean active,
                                         UUID parentCategoryId, boolean clearParentCategory, long version) {
    }

    /** Round 18: moves every item (active or not) from this category into {@code targetCategoryId},
     * then removes the now-empty source category - the "merge two near-duplicate categories into
     * one" action (e.g. accidentally-created "Beverage" merged into the real "Beverages"). */
    public record MergeCategoryRequest(@NotNull UUID targetCategoryId) {
    }

    /** {@code foodType} is optional (VEG/EGG/NON_VEG) - null leaves it to {@code MenuItem}'s own
     * {@code @Builder.Default} of VEG, mirroring how {@code vegetarian} is kept alongside it.
     *
     * <p>{@code branchId} (Bistrodesk branch-isolation release, requirement #1, user-confirmed
     * decision: "strictly for the branch where it is getting created, not shared with any other
     * branch") is optional here only because the server resolves this caller's own default/sole
     * branch when omitted (see {@code BranchAccessService#resolveEffectiveBranchId}) - a
     * multi-branch/unrestricted caller with no default MUST supply one or the request is rejected
     * (BRANCH_REQUIRED); it no longer falls back to a shared/centralized item the way Phase 3
     * originally allowed - see {@code MenuController}'s javadoc. */
    public record CreateItemRequest(@NotNull UUID categoryId, @NotBlank String name, String sku, String plu,
                                     String description, @NotNull @PositiveOrZero BigDecimal price,
                                     String taxCode, UUID stationId, boolean vegetarian, String foodType,
                                     boolean directSale, BigDecimal halfPrice, String barcode, Integer prepTimeMinutes,
                                     UUID branchId) {
    }

    /**
     * Any null field (except version) leaves that attribute unchanged - EXCEPT {@code stationId},
     * which has no way to distinguish "leave as-is" from "clear it" through a plain null. Use
     * {@code clearStation=true} to explicitly unset a previously-assigned station. {@code halfPrice}
     * follows the same "null = leave unchanged" rule as everything else here, which means once a
     * half price is set there's no way to clear it back to "no half portion" through this endpoint
     * alone - acceptable for a first pass (setting it to the same value as {@code price} has the
     * same practical effect of "no real discount for Half", and a dedicated clear flag can be added
     * the same way {@code clearStation} was if that turns out to matter). {@code barcode}/{@code
     * prepTimeMinutes} follow the same rule - blank string clears barcode, there's no way to clear
     * prepTimeMinutes back to null once set (same accepted limitation as halfPrice above).
     */
    public record UpdateItemRequest(String name, BigDecimal price, String taxCode, UUID stationId,
                                     boolean clearStation, Boolean vegetarian, String foodType, Boolean available,
                                     Boolean active, Boolean directSale, BigDecimal halfPrice, String barcode,
                                     Integer prepTimeMinutes,
                                     // Bistrodesk branch-isolation release (requirement #1): reassigns this item to
                                     // a different branch this caller can reach - null/omitted leaves it unchanged.
                                     // Unlike stationId, there is deliberately no "clearBranch" flag anymore: Phase
                                     // 3 originally let an item be moved back onto a shared/centralized menu (branch
                                     // = null), but that's exactly the "leaks into every branch" design this release
                                     // reverses (see MenuController's javadoc) - every item keeps a real branch for
                                     // its whole life once created.
                                     UUID branchId,
                                     long version) {
    }

    /** Round 10: a dedicated endpoint (rather than adding a field to {@link UpdateItemRequest}) so
     * the AI-written-description feature - and manual description edits - never have to reconstruct
     * every other field of that already-widely-called record just to change one string. Empty string
     * clears a description, same "blank is a real value, only null/omission would mean unchanged"
     * convention as {@code Restaurant.logoImageBase64}. */
    public record UpdateDescriptionRequest(String description, long version) {
    }
}
