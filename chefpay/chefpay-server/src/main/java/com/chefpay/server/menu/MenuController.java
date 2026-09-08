package com.chefpay.server.menu;

import com.chefpay.core.domain.AppUser;
import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.FoodType;
import com.chefpay.core.domain.KitchenStation;
import com.chefpay.core.domain.MenuCategory;
import com.chefpay.core.domain.MenuItem;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.KitchenStationRepository;
import com.chefpay.core.repository.MenuCategoryRepository;
import com.chefpay.core.repository.MenuItemRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.chefpay.server.websocket.WebSocketEventPublisher;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bistrodesk branch-isolation release (requirement #1, user-confirmed decision: "strictly for the
 * branch where it is getting created, not shared with any other branch"): {@code MenuItem.branch}
 * is now mandatory going forward, reversing Phase 3's "null = shared/centralized, visible to every
 * branch" design (see {@code MenuItem#getBranch()}'s javadoc history) - a null-branch item was
 * exactly the confirmed real-world bug ("menu items showing in each other's branch"). {@code
 * createItem} resolves the effective branch the same mandatory way Order/Customer/Inventory do -
 * {@link BranchAccessService#resolveEffectiveBranchId} - never leaving a new item branchless. Any
 * item created before this release that is still branchless gets backfilled onto the install's
 * oldest branch by {@code DataSeeder#ensureMenuItemBranchBackfill} - see its javadoc - so the
 * {@code item.getBranch() == null} tolerance still in {@link #visible} is a startup-only safety
 * net, not a normal runtime path.
 *
 * <p>Categories are deliberately NOT branch-scoped - the taxonomy ("Beverages", "Main Course") is
 * a shared shape every branch's menu fits into, and the thing that actually varies per branch is
 * which items exist, not the category names themselves; scoping categories too would additionally
 * reopen {@code MenuCategory#name}'s global-unique constraint the same way Bistrodesk Phase 2 had
 * to for {@code InventoryItem#name}, for a dimension this feature doesn't need.
 *
 * <p>{@code list()}'s {@code branchId} filter/{@code visible} check are unchanged from Phase 3: an
 * explicit {@code branchId} is access-checked via {@link BranchAccessService} the same way Table/
 * Inventory listing already is, and an omitted one defaults to every one of the caller's accessible
 * branches merged together - deliberately kept as the aggregate, multi-branch Menu Editor view (an
 * Owner/Admin managing several branches' menus from one screen), the same convention Reports/Tax/
 * Discount config already use; the POS-facing order-taking screen ({@code PosTerminalPage}) already
 * passes its own terminal's {@code branchId} explicitly, so it only ever sees that one branch's
 * items once every item has a real branch.
 */
@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuCategoryRepository categoryRepository;
    private final MenuItemRepository itemRepository;
    private final KitchenStationRepository stationRepository;
    private final BranchRepository branchRepository;
    private final BranchAccessService branchAccessService;
    private final WebSocketEventPublisher eventPublisher;

    @GetMapping
    public ApiResponse<List<MenuDtos.CategoryDto>> list(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @RequestParam(required = false) UUID branchId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // includeInactive is for Menu Editor (requires MENU_MANAGE to actually see anything useful
        // from it - an inactive category returns its inactive items too) so staff can find and
        // reactivate a soft-deleted category/item; the POS-facing default (false) stays exactly
        // today's active-only behavior so no other caller needs to change.
        if (includeInactive && !hasMenuManage()) {
            throw ApiException.forbidden("MENU_MANAGE required to view inactive menu entries");
        }
        Set<UUID> accessible = resolveAccessibleBranchIds(branchId, principal);
        List<MenuItem> allItems = (includeInactive ? itemRepository.findAll() : itemRepository.findByActiveTrueOrderByNameAsc())
                .stream().filter(i -> visible(i, accessible)).toList();
        List<MenuCategory> categories = includeInactive
                ? categoryRepository.findAll().stream()
                        .sorted(java.util.Comparator.comparingInt(MenuCategory::getDisplayOrder))
                        .toList()
                : categoryRepository.findByActiveTrueOrderByDisplayOrderAsc();
        List<MenuDtos.CategoryDto> dtos = categories.stream()
                .map(c -> toCategoryDto(c, allItems))
                .toList();
        return ApiResponse.ok(dtos);
    }

    private Set<UUID> resolveAccessibleBranchIds(UUID requestedBranchId, AuthenticatedPrincipal principal) {
        AppUser requester = branchAccessService.resolve(principal);
        if (requestedBranchId != null) {
            branchAccessService.assertAccess(requester, requestedBranchId);
            return Set.of(requestedBranchId);
        }
        return branchAccessService.accessibleBranchIds(requester);
    }

    private boolean visible(MenuItem item, Set<UUID> accessibleBranchIds) {
        return accessibleBranchIds == null || item.getBranch() == null
                || accessibleBranchIds.contains(item.getBranch().getId());
    }

    private MenuDtos.CategoryDto toCategoryDto(MenuCategory c, List<MenuItem> allItems) {
        List<MenuDtos.ItemDto> items = allItems.stream()
                .filter(i -> i.getCategory().getId().equals(c.getId()))
                .sorted(java.util.Comparator.comparing(MenuItem::getName))
                .map(this::toItemDto)
                .toList();
        MenuCategory parent = c.getParentCategory();
        return new MenuDtos.CategoryDto(c.getId(), c.getName(), c.getDisplayOrder(), c.isActive(), c.getVersion(), items,
                parent == null ? null : parent.getId(), parent == null ? null : parent.getName());
    }

    private boolean hasMenuManage() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("MENU_MANAGE"));
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<MenuDtos.CategoryDto> createCategory(@Valid @RequestBody MenuDtos.CreateCategoryRequest request) {
        MenuCategory parent = resolveParent(request.parentCategoryId());
        MenuCategory saved = categoryRepository.save(MenuCategory.builder()
                .name(request.name())
                .displayOrder(request.displayOrder())
                .parentCategory(parent)
                .build());
        return ApiResponse.ok(toCategoryDto(saved, List.of()));
    }

    @PatchMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    @Transactional
    public ApiResponse<MenuDtos.CategoryDto> updateCategory(@PathVariable UUID id, @RequestBody MenuDtos.UpdateCategoryRequest request) {
        MenuCategory category = categoryRepository.findById(id).orElseThrow(() -> ApiException.notFound("Category not found"));
        if (category.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(MenuCategory.class, id);
        }
        if (request.name() != null) category.setName(request.name());
        if (request.displayOrder() != null) category.setDisplayOrder(request.displayOrder());
        if (request.active() != null) category.setActive(request.active());
        if (request.clearParentCategory()) {
            category.setParentCategory(null);
        } else if (request.parentCategoryId() != null) {
            category.setParentCategory(resolveParent(request.parentCategoryId(), id));
        }
        MenuCategory saved = categoryRepository.save(category);
        List<MenuItem> items = itemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(i -> i.getCategory().getId().equals(saved.getId()))
                .toList();
        return ApiResponse.ok(toCategoryDto(saved, items));
    }

    /** Round 18: a genuinely empty category (no items at all, active or not, and no subcategories
     * under it) can be hard-deleted outright - nothing references it, so there's no data at risk.
     * A non-empty one 409s with a clear pointer to the merge endpoint instead of silently no-op'ing
     * or (worse) cascading a delete that would orphan real menu items. */
    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    @Transactional
    public ApiResponse<Void> deleteCategory(@PathVariable UUID id) {
        MenuCategory category = categoryRepository.findById(id).orElseThrow(() -> ApiException.notFound("Category not found"));
        long itemCount = itemRepository.countByCategoryId(id);
        if (itemCount > 0) {
            throw ApiException.conflict("CATEGORY_NOT_EMPTY",
                    "\"" + category.getName() + "\" has " + itemCount + " item(s). Merge it into another category first "
                            + "(or move/delete its items) before deleting it.");
        }
        if (categoryRepository.existsByParentCategoryId(id)) {
            throw ApiException.conflict("CATEGORY_HAS_SUBCATEGORIES",
                    "\"" + category.getName() + "\" has subcategories under it. Reassign or remove those first.");
        }
        categoryRepository.delete(category);
        return ApiResponse.ok(null);
    }

    /** Round 18: "if any category already exist the item should be added on the same, no need to
     * create a new duplicate category" - this is the manual cleanup counterpart for categories that
     * already ended up duplicated (e.g. an AI import that ran before this fix created "Beverage"
     * next to an existing "Beverages"). Moves every item (active or not) from {@code id} into
     * {@code targetCategoryId}, then removes the now-empty source. Refuses to merge a category that
     * itself has subcategories (keeps the one-level-deep hierarchy invariant simple - reassign those
     * first) or to merge a category into itself. */
    @PostMapping("/categories/{id}/merge")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    @Transactional
    public ApiResponse<MenuDtos.CategoryDto> mergeCategory(@PathVariable UUID id, @Valid @RequestBody MenuDtos.MergeCategoryRequest request) {
        if (id.equals(request.targetCategoryId())) {
            throw ApiException.badRequest("INVALID_MERGE_TARGET", "A category can't be merged into itself.");
        }
        MenuCategory source = categoryRepository.findById(id).orElseThrow(() -> ApiException.notFound("Category not found"));
        MenuCategory target = categoryRepository.findById(request.targetCategoryId())
                .orElseThrow(() -> ApiException.notFound("Target category not found"));
        if (categoryRepository.existsByParentCategoryId(id)) {
            throw ApiException.conflict("CATEGORY_HAS_SUBCATEGORIES",
                    "\"" + source.getName() + "\" has subcategories under it. Reassign or remove those first.");
        }
        List<MenuItem> movedItems = itemRepository.findByCategoryId(id);
        for (MenuItem item : movedItems) {
            item.setCategory(target);
        }
        itemRepository.saveAll(movedItems);
        categoryRepository.delete(source);

        List<MenuItem> targetItems = itemRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(i -> i.getCategory().getId().equals(target.getId()))
                .toList();
        return ApiResponse.ok(toCategoryDto(target, targetItems));
    }

    private MenuCategory resolveParent(UUID parentId) {
        return resolveParent(parentId, null);
    }

    /** {@code selfId} (null on create) guards against a category becoming its own parent; either
     * way, a category that is ITSELF already a subcategory can't be used as a parent - this keeps
     * the hierarchy capped at one level deep (see MenuCategory#parentCategory's javadoc). */
    private MenuCategory resolveParent(UUID parentId, UUID selfId) {
        if (parentId == null) {
            return null;
        }
        if (parentId.equals(selfId)) {
            throw ApiException.badRequest("INVALID_PARENT_CATEGORY", "A category can't be its own parent.");
        }
        MenuCategory parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> ApiException.notFound("Parent category not found"));
        if (parent.getParentCategory() != null) {
            throw ApiException.badRequest("INVALID_PARENT_CATEGORY",
                    "\"" + parent.getName() + "\" is itself a subcategory - categories can only be nested one level deep.");
        }
        return parent;
    }

    @PostMapping("/items")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    public ApiResponse<MenuDtos.ItemDto> createItem(@Valid @RequestBody MenuDtos.CreateItemRequest request,
                                                     @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        MenuCategory category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> ApiException.notFound("Category not found"));
        KitchenStation station = request.stationId() == null ? null : stationRepository.findById(request.stationId())
                .orElseThrow(() -> ApiException.notFound("Kitchen station not found"));
        // Bistrodesk branch-isolation release (requirement #1): mandatory, same resolution Order/
        // Customer/Inventory use - never leaves a new item branchless.
        AppUser requester = branchAccessService.resolve(principal);
        UUID resolvedBranchId = branchAccessService.resolveEffectiveBranchId(requester, request.branchId());
        Branch branch = branchRepository.findById(resolvedBranchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        MenuItem saved = itemRepository.save(MenuItem.builder()
                .category(category)
                .name(request.name())
                .sku(request.sku())
                .plu(request.plu())
                .description(request.description())
                .price(request.price())
                .taxCode(request.taxCode())
                .station(station)
                .vegetarian(request.vegetarian())
                .foodType(request.foodType() == null ? FoodType.VEG : FoodType.valueOf(request.foodType()))
                .directSale(request.directSale())
                .halfPrice(request.halfPrice())
                .barcode(request.barcode())
                .prepTimeMinutes(request.prepTimeMinutes())
                .branch(branch)
                .build());
        return ApiResponse.ok(toItemDto(saved));
    }

    @PatchMapping("/items/{id}")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    @Transactional
    public ApiResponse<MenuDtos.ItemDto> updateItem(@PathVariable UUID id, @RequestBody MenuDtos.UpdateItemRequest request,
                                                     @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        MenuItem item = itemRepository.findById(id).orElseThrow(() -> ApiException.notFound("Menu item not found"));
        AppUser requester = branchAccessService.resolve(principal);
        // Bistrodesk branch-isolation release (requirement #1): a restricted caller can only touch
        // an item already at one of their own branches - and, symmetrically, can only MOVE an item
        // onto a branch they themselves can reach (never used to reassign an item to a branch out
        // of their own control). The null-branch tolerance here is a startup-only safety net for a
        // not-yet-backfilled legacy row (see DataSeeder#ensureMenuItemBranchBackfill).
        branchAccessService.assertAccess(requester, item.getBranch() == null ? null : item.getBranch().getId());
        if (item.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(MenuItem.class, id);
        }

        if (request.name() != null) item.setName(request.name());
        if (request.price() != null) item.setPrice(request.price());
        if (request.taxCode() != null) item.setTaxCode(request.taxCode());
        if (request.vegetarian() != null) item.setVegetarian(request.vegetarian());
        if (request.foodType() != null) item.setFoodType(FoodType.valueOf(request.foodType()));
        if (request.active() != null) item.setActive(request.active());
        if (request.directSale() != null) item.setDirectSale(request.directSale());
        if (request.halfPrice() != null) item.setHalfPrice(request.halfPrice());
        if (request.barcode() != null) item.setBarcode(request.barcode().isBlank() ? null : request.barcode());
        if (request.prepTimeMinutes() != null) item.setPrepTimeMinutes(request.prepTimeMinutes());

        if (request.branchId() != null) {
            branchAccessService.assertAccess(requester, request.branchId());
            item.setBranch(branchRepository.findById(request.branchId())
                    .orElseThrow(() -> ApiException.notFound("Branch not found")));
        }

        if (request.clearStation()) {
            item.setStation(null);
        } else if (request.stationId() != null) {
            item.setStation(stationRepository.findById(request.stationId())
                    .orElseThrow(() -> ApiException.notFound("Kitchen station not found")));
        }

        boolean availabilityChanged = false;
        if (request.available() != null && item.isAvailable() != request.available()) {
            item.setAvailable(request.available());
            availabilityChanged = true;
        }

        MenuItem saved = itemRepository.save(item);

        if (availabilityChanged) {
            eventPublisher.publish("/topic/menu", "MENU_AVAILABILITY_CHANGED", saved.getId(), saved.getVersion(),
                    Map.of("itemName", saved.getName(), "available", saved.isAvailable()));
        }

        return ApiResponse.ok(toItemDto(saved));
    }

    @PatchMapping("/items/{id}/description")
    @PreAuthorize("hasAuthority('MENU_MANAGE')")
    @Transactional
    public ApiResponse<MenuDtos.ItemDto> updateDescription(@PathVariable UUID id, @RequestBody MenuDtos.UpdateDescriptionRequest request) {
        MenuItem item = itemRepository.findById(id).orElseThrow(() -> ApiException.notFound("Menu item not found"));
        if (item.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(MenuItem.class, id);
        }
        item.setDescription(request.description() == null || request.description().isBlank() ? null : request.description());
        return ApiResponse.ok(toItemDto(itemRepository.save(item)));
    }

    private MenuDtos.ItemDto toItemDto(MenuItem i) {
        return new MenuDtos.ItemDto(i.getId(), i.getCategory().getId(), i.getName(), i.getSku(), i.getPlu(),
                i.getDescription(), i.getPrice(), i.getTaxCode(),
                i.getStation() == null ? null : i.getStation().getId(),
                i.getStation() == null ? null : i.getStation().getName(),
                i.isVegetarian(), i.getFoodType().name(), i.isAvailable(), i.isActive(), i.isDirectSale(),
                i.getHalfPrice(), i.getBarcode(), i.getPrepTimeMinutes(), i.getVersion(),
                i.getBranch() == null ? null : i.getBranch().getId(),
                i.getBranch() == null ? null : i.getBranch().getName());
    }
}
