package com.chefpay.server.restaurant;

import com.chefpay.core.domain.Branch;
import com.chefpay.core.domain.Floor;
import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.BranchRepository;
import com.chefpay.core.repository.FloorRepository;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.server.auth.AuthenticatedPrincipal;
import com.chefpay.server.branch.BranchAccessService;
import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/restaurant")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantRepository restaurantRepository;
    private final BranchRepository branchRepository;
    private final FloorRepository floorRepository;
    /** Bistrodesk follow-up requirement #9 ("in the setting tab, there are branches list
     * displaying at the bottom, it should only display the current branch"): {@link #toDto}'s
     * embedded {@code branches} list used to be every branch on the install, unconditionally, for
     * ANY authenticated caller (this endpoint carries no {@code @PreAuthorize} at all - every
     * logged-in Waiter/Cashier can call it just to read Settings/currency/etc). See {@link #get}. */
    private final BranchAccessService branchAccessService;

    @GetMapping
    public ApiResponse<RestaurantDto> get(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
        return ApiResponse.ok(toDto(restaurant, branchAccessService.accessibleBranchIds(principal)));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE') or hasAuthority('ORGANIZATION_MANAGE')")
    @Transactional
    public ApiResponse<RestaurantDto> update(@Valid @RequestBody UpdateRestaurantRequest request,
                                              @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // Phase 2 (Section F): every Organization-shaped edit is admin-tier, so it requires a
        // password-authenticated session even though this endpoint predates that requirement and
        // also carries a lot of non-"Organization" Settings fields (SMTP/AI/etc.) - all of those are
        // equally admin-tier configuration already gated on RESTAURANT_MANAGE, so extending the same
        // password-login requirement here is a tightening, not a behavior change for any legitimate
        // caller (every AppUser always has a password - see UserAccountService#createUser).
        principal.requirePasswordLogin();
        Restaurant restaurant = restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
        if (restaurant.getVersion() != request.version()) {
            throw new ObjectOptimisticLockingFailureException(Restaurant.class, restaurant.getId());
        }
        if (request.name() != null) restaurant.setName(request.name());
        if (request.organizationId() != null) restaurant.setOrganizationId(request.organizationId());
        if (request.organizationName() != null) restaurant.setOrganizationName(request.organizationName());
        if (request.contactEmail() != null) restaurant.setContactEmail(request.contactEmail());
        if (request.addressLine1() != null) restaurant.setAddressLine1(request.addressLine1());
        if (request.addressLine2() != null) restaurant.setAddressLine2(request.addressLine2());
        if (request.city() != null) restaurant.setCity(request.city());
        if (request.state() != null) restaurant.setState(request.state());
        if (request.postalCode() != null) restaurant.setPostalCode(request.postalCode());
        if (request.country() != null) restaurant.setCountry(request.country());
        if (request.currencySymbol() != null) restaurant.setCurrencySymbol(request.currencySymbol());
        if (request.defaultTimezone() != null) restaurant.setDefaultTimezone(request.defaultTimezone());
        if (request.gstin() != null) restaurant.setGstin(request.gstin());
        if (request.supportPhone() != null) restaurant.setSupportPhone(request.supportPhone());
        if (request.serviceChargePercent() != null) restaurant.setServiceChargePercent(request.serviceChargePercent());
        if (request.requireKitchenSyncForServed() != null) restaurant.setRequireKitchenSyncForServed(request.requireKitchenSyncForServed());
        if (request.receiptFooterText() != null) restaurant.setReceiptFooterText(request.receiptFooterText());
        if (request.onlineOrderZomatoEnabled() != null) restaurant.setOnlineOrderZomatoEnabled(request.onlineOrderZomatoEnabled());
        if (request.onlineOrderSwiggyEnabled() != null) restaurant.setOnlineOrderSwiggyEnabled(request.onlineOrderSwiggyEnabled());
        if (request.enabledPaymentMethods() != null) restaurant.setEnabledPaymentMethods(request.enabledPaymentMethods());
        if (request.upiVpaId() != null) restaurant.setUpiVpaId(request.upiVpaId());
        if (request.upiPayeeName() != null) restaurant.setUpiPayeeName(request.upiPayeeName());
        if (request.cardPaymentEnabled() != null) restaurant.setCardPaymentEnabled(request.cardPaymentEnabled());
        if (request.cardTerminalNote() != null) restaurant.setCardTerminalNote(request.cardTerminalNote());
        if (request.cashDrawerEnabled() != null) restaurant.setCashDrawerEnabled(request.cashDrawerEnabled());
        if (request.receiptPrinterName() != null) restaurant.setReceiptPrinterName(request.receiptPrinterName());
        if (request.receiptPaperWidthChars() != null) restaurant.setReceiptPaperWidthChars(request.receiptPaperWidthChars());
        if (request.autoPrintOnlineOrders() != null) restaurant.setAutoPrintOnlineOrders(request.autoPrintOnlineOrders());
        if (request.autoPrintReceiptOnPayment() != null) restaurant.setAutoPrintReceiptOnPayment(request.autoPrintReceiptOnPayment());
        if (request.deliveryBoyFeatureEnabled() != null) restaurant.setDeliveryBoyFeatureEnabled(request.deliveryBoyFeatureEnabled());
        if (request.kotOptionalEnabled() != null) restaurant.setKotOptionalEnabled(request.kotOptionalEnabled());
        if (request.smtpHost() != null) restaurant.setSmtpHost(request.smtpHost());
        if (request.smtpPort() != null) restaurant.setSmtpPort(request.smtpPort());
        if (request.smtpUsername() != null) restaurant.setSmtpUsername(request.smtpUsername());
        // Same empty-string-clears convention as aiApiKey/logoImageBase64 above.
        if (request.smtpPassword() != null) restaurant.setSmtpPassword(request.smtpPassword());
        if (request.smtpFromAddress() != null) restaurant.setSmtpFromAddress(request.smtpFromAddress());
        if (request.smtpUseTls() != null) restaurant.setSmtpUseTls(request.smtpUseTls());
        // Empty string (not null - null means "unchanged", same as every other field here) clears
        // a previously-set logo, matching how receiptFooterText already lets a blank value through.
        if (request.logoImageBase64() != null) restaurant.setLogoImageBase64(request.logoImageBase64());
        if (request.aiFeaturesEnabled() != null) restaurant.setAiFeaturesEnabled(request.aiFeaturesEnabled());
        if (request.aiProvider() != null) restaurant.setAiProvider(request.aiProvider());
        // Same empty-string-clears convention as logoImageBase64 above.
        if (request.aiApiKey() != null) restaurant.setAiApiKey(request.aiApiKey());
        if (request.aiModel() != null) restaurant.setAiModel(request.aiModel());
        if (request.aiMenuImportEnabled() != null) restaurant.setAiMenuImportEnabled(request.aiMenuImportEnabled());
        if (request.aiInsightsChatEnabled() != null) restaurant.setAiInsightsChatEnabled(request.aiInsightsChatEnabled());
        if (request.aiReorderDraftsEnabled() != null) restaurant.setAiReorderDraftsEnabled(request.aiReorderDraftsEnabled());
        if (request.aiAnomalyFlaggingEnabled() != null) restaurant.setAiAnomalyFlaggingEnabled(request.aiAnomalyFlaggingEnabled());
        if (request.aiMenuDescriptionsEnabled() != null) restaurant.setAiMenuDescriptionsEnabled(request.aiMenuDescriptionsEnabled());
        if (request.aiNightlySummaryEnabled() != null) restaurant.setAiNightlySummaryEnabled(request.aiNightlySummaryEnabled());
        if (request.dashboardViewMode() != null) restaurant.setDashboardViewMode(request.dashboardViewMode());
        if (request.kitchenServiceMode() != null) restaurant.setKitchenServiceMode(request.kitchenServiceMode());
        if (request.showDiscountConfirmation() != null) restaurant.setShowDiscountConfirmation(request.showDiscountConfirmation());
        if (request.poApprovalRequired() != null) restaurant.setPoApprovalRequired(request.poApprovalRequired());
        if (request.aiReplenishmentNotesEnabled() != null) restaurant.setAiReplenishmentNotesEnabled(request.aiReplenishmentNotesEnabled());
        if (request.ocrUseAiVisionAssist() != null) restaurant.setOcrUseAiVisionAssist(request.ocrUseAiVisionAssist());
        if (request.biometricOverrideEnabled() != null) restaurant.setBiometricOverrideEnabled(request.biometricOverrideEnabled());
        if (request.autoPurgeEnabled() != null) restaurant.setAutoPurgeEnabled(request.autoPurgeEnabled());
        if (request.dataRetentionDays() != null) restaurant.setDataRetentionDays(request.dataRetentionDays());
        if (request.defaultOpeningFloat() != null) restaurant.setDefaultOpeningFloat(request.defaultOpeningFloat());
        if (request.cashVarianceThreshold() != null) restaurant.setCashVarianceThreshold(request.cashVarianceThreshold());
        if (request.eodZReportRecipientEmails() != null) restaurant.setEodZReportRecipientEmails(request.eodZReportRecipientEmails());
        if (request.marginErosionThresholdPercent() != null) restaurant.setMarginErosionThresholdPercent(request.marginErosionThresholdPercent());
        if (request.autoPoFromSuggestionsEnabled() != null) restaurant.setAutoPoFromSuggestionsEnabled(request.autoPoFromSuggestionsEnabled());
        if (request.criticalAlertEscalationMinutes() != null) restaurant.setCriticalAlertEscalationMinutes(request.criticalAlertEscalationMinutes());
        if (request.criticalAlertRecipientEmails() != null) restaurant.setCriticalAlertRecipientEmails(request.criticalAlertRecipientEmails());
        if (request.nlAssistantWriteCommandsEnabled() != null) restaurant.setNlAssistantWriteCommandsEnabled(request.nlAssistantWriteCommandsEnabled());
        if (request.menuCentralized() != null) restaurant.setMenuCentralized(request.menuCentralized());
        if (request.itemLevelKitchenStatusEnabled() != null) restaurant.setItemLevelKitchenStatusEnabled(request.itemLevelKitchenStatusEnabled());
        return ApiResponse.ok(toDto(restaurantRepository.save(restaurant), branchAccessService.accessibleBranchIds(principal)));
    }

    // Bistrodesk branch-isolation release (requirement #2, user-confirmed decision): this legacy
    // POST /api/restaurant/branches endpoint (predating the dedicated BranchController, and never
    // called from any current chefpay-web UI - confirmed by grep) is REMOVED, not just left alone.
    // It was gated only by RESTAURANT_MANAGE (a permission OWNER/ADMIN hold by default), never ran
    // the MULTI_BRANCH/plan-capacity check BranchController#create used to run (that endpoint is
    // now itself removed too - see PlatformOwnerController#assertCanAddAnotherBranch, where the
    // same check now lives, the only place left that runs it), and never even
    // set branchCode - a second, weaker, buggier path to the exact POS-reachable branch-creation
    // capability this release requires to not exist anywhere outside the platform-owner console.
    // Removing it (rather than leaving a dead-but-callable duplicate) is what actually satisfies
    // "POS must not show any branch-creation option to normal users" against a direct API call, not
    // just the UI - see PlatformOwnerController#createBranch for the one remaining, correctly-gated
    // path this capability now has.

    @PostMapping("/branches/{branchId}/floors")
    @PreAuthorize("hasAuthority('RESTAURANT_MANAGE')")
    public ApiResponse<RestaurantDto.FloorDto> createFloor(@PathVariable UUID branchId, @Valid @RequestBody CreateFloorRequest request) {
        Branch branch = branchRepository.findById(branchId).orElseThrow(() -> ApiException.notFound("Branch not found"));
        Floor floor = Floor.builder().branch(branch).name(request.name()).displayOrder(request.displayOrder()).build();
        Floor saved = floorRepository.save(floor);
        return ApiResponse.ok(new RestaurantDto.FloorDto(saved.getId(), saved.getName(), saved.getDisplayOrder(), saved.getVersion()));
    }

    private RestaurantDto toDto(Restaurant restaurant, Set<UUID> allowedBranchIds) {
        // Bistrodesk follow-up requirement #10 (query optimization pass): this used to call
        // floorRepository.findAllByOrderByDisplayOrderAsc() - fetching every floor on the whole
        // install - once PER BRANCH inside the .map() below (an N-times-over full-table-scan, not
        // just an N+1, since GET /api/restaurant is polled by every page via useRestaurantConfig()).
        // Fetched once up front and grouped by branch id in memory instead - one query total,
        // regardless of how many branches exist.
        Map<UUID, List<RestaurantDto.FloorDto>> floorsByBranch = floorRepository.findAllByOrderByDisplayOrderAsc().stream()
                .collect(Collectors.groupingBy(
                        f -> f.getBranch().getId(),
                        Collectors.mapping(
                                f -> new RestaurantDto.FloorDto(f.getId(), f.getName(), f.getDisplayOrder(), f.getVersion()),
                                Collectors.toList())));
        List<RestaurantDto.BranchDto> branches = branchRepository.findAll().stream()
                .filter(b -> b.getRestaurant().getId().equals(restaurant.getId()))
                // Bistrodesk follow-up requirement #9: null (unrestricted - OWNER/ADMIN, or any
                // account holding BranchAccessService#VIEW_ALL_BRANCHES) means "every branch," same
                // as every other accessibleBranchIds() consumer; a branch-restricted caller only
                // ever sees their own assigned branch(es) here, matching BranchController#list's
                // identical fix for requirement #8 (the caller of this fallback list, in
                // BranchesTerminalsPage.tsx, is precisely a Waiter/Cashier who lacks BRANCH_MANAGE).
                .filter(b -> allowedBranchIds == null || allowedBranchIds.contains(b.getId()))
                .map(b -> new RestaurantDto.BranchDto(b.getId(), b.getName(), b.getAddress(), b.getPhone(), b.getVersion(),
                        b.isManualKotPrintEnabled(), floorsByBranch.getOrDefault(b.getId(), List.of())))
                .toList();
        return new RestaurantDto(restaurant.getId(), restaurant.getName(),
                restaurant.getOrganizationId(), restaurant.getOrganizationName(),
                restaurant.getContactEmail(), restaurant.getAddressLine1(), restaurant.getAddressLine2(),
                restaurant.getCity(), restaurant.getState(), restaurant.getPostalCode(), restaurant.getCountry(),
                restaurant.getStatus() == null ? null : restaurant.getStatus().name(),
                restaurant.getCurrencySymbol(),
                restaurant.getDefaultTimezone(), restaurant.getGstin(), restaurant.getSupportPhone(),
                restaurant.getServiceChargePercent(), restaurant.isRequireKitchenSyncForServed(),
                restaurant.getReceiptFooterText(), restaurant.isOnlineOrderZomatoEnabled(),
                restaurant.isOnlineOrderSwiggyEnabled(), restaurant.getEnabledPaymentMethods(),
                restaurant.getUpiVpaId(), restaurant.getUpiPayeeName(), restaurant.isCardPaymentEnabled(),
                restaurant.getCardTerminalNote(), restaurant.isCashDrawerEnabled(), restaurant.getReceiptPrinterName(),
                restaurant.getReceiptPaperWidthChars(), restaurant.isAutoPrintOnlineOrders(),
                restaurant.isAutoPrintReceiptOnPayment(),
                restaurant.isDeliveryBoyFeatureEnabled(), restaurant.isKotOptionalEnabled(), restaurant.getLogoImageBase64(),
                restaurant.getSmtpHost(), restaurant.getSmtpPort(), restaurant.getSmtpUsername(),
                restaurant.getSmtpPassword() != null && !restaurant.getSmtpPassword().isBlank(),
                restaurant.getSmtpFromAddress(), restaurant.isSmtpUseTls(),
                restaurant.isAiFeaturesEnabled(), restaurant.getAiProvider(), restaurant.getAiModel(),
                restaurant.getAiApiKey() != null && !restaurant.getAiApiKey().isBlank(),
                restaurant.isAiMenuImportEnabled(), restaurant.isAiInsightsChatEnabled(),
                restaurant.isAiReorderDraftsEnabled(), restaurant.isAiAnomalyFlaggingEnabled(),
                restaurant.isAiMenuDescriptionsEnabled(), restaurant.isAiNightlySummaryEnabled(),
                restaurant.getDashboardViewMode(), restaurant.getKitchenServiceMode(),
                restaurant.isShowDiscountConfirmation(), restaurant.isPoApprovalRequired(),
                restaurant.isAiReplenishmentNotesEnabled(),
                restaurant.isOcrUseAiVisionAssist(), restaurant.isBiometricOverrideEnabled(),
                restaurant.isAutoPurgeEnabled(), restaurant.getDataRetentionDays(),
                restaurant.getDefaultOpeningFloat(), restaurant.getCashVarianceThreshold(),
                restaurant.getEodZReportRecipientEmails(), restaurant.getMarginErosionThresholdPercent(),
                restaurant.isAutoPoFromSuggestionsEnabled(), restaurant.getCriticalAlertEscalationMinutes(),
                restaurant.getCriticalAlertRecipientEmails(), restaurant.isNlAssistantWriteCommandsEnabled(),
                restaurant.isMenuCentralized(),
                restaurant.isItemLevelKitchenStatusEnabled(),
                restaurant.getVersion(), branches);
    }
}
