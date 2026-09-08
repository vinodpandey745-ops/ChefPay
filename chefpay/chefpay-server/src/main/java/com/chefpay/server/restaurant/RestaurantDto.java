package com.chefpay.server.restaurant;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RestaurantDto(
        UUID id,
        String name,
        // Round 17: Organization/Branch/Terminal identity model - see Restaurant.java's javadoc.
        String organizationId,
        String organizationName,
        // Phase 2: Organization profile fields (item 4 of the request). status is read-only here -
        // see Restaurant#status's javadoc for why it's set only via PlatformOwnerController, never
        // through this DTO/the restaurant's own Settings screen.
        String contactEmail,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String country,
        String status,
        String currencySymbol,
        String defaultTimezone,
        String gstin,
        String supportPhone,
        BigDecimal serviceChargePercent,
        boolean requireKitchenSyncForServed,
        String receiptFooterText,
        boolean onlineOrderZomatoEnabled,
        boolean onlineOrderSwiggyEnabled,
        String enabledPaymentMethods,
        String upiVpaId,
        String upiPayeeName,
        boolean cardPaymentEnabled,
        String cardTerminalNote,
        boolean cashDrawerEnabled,
        String receiptPrinterName,
        int receiptPaperWidthChars,
        boolean autoPrintOnlineOrders,
        boolean autoPrintReceiptOnPayment,
        boolean deliveryBoyFeatureEnabled,
        boolean kotOptionalEnabled,
        String logoImageBase64,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        boolean smtpPasswordConfigured,
        String smtpFromAddress,
        boolean smtpUseTls,
        boolean aiFeaturesEnabled,
        String aiProvider,
        String aiModel,
        boolean aiApiKeyConfigured,
        boolean aiMenuImportEnabled,
        boolean aiInsightsChatEnabled,
        boolean aiReorderDraftsEnabled,
        boolean aiAnomalyFlaggingEnabled,
        boolean aiMenuDescriptionsEnabled,
        boolean aiNightlySummaryEnabled,
        String dashboardViewMode,
        String kitchenServiceMode,
        boolean showDiscountConfirmation,
        boolean poApprovalRequired,
        boolean aiReplenishmentNotesEnabled,
        // Final round - F2.3 (OCR invoice intake) / F4.5 (biometric manager auth) feature flags.
        boolean ocrUseAiVisionAssist,
        boolean biometricOverrideEnabled,
        // Final round (post-delivery follow-up) - data-retention auto-purge master switch/window.
        boolean autoPurgeEnabled,
        int dataRetentionDays,
        // Round 17 desktop-parity port: EOD/loss-prevention/AI-backbone fields that already existed
        // on the Restaurant entity (Rounds 13-14) but were never exposed to any client DTO before.
        BigDecimal defaultOpeningFloat,
        BigDecimal cashVarianceThreshold,
        String eodZReportRecipientEmails,
        BigDecimal marginErosionThresholdPercent,
        boolean autoPoFromSuggestionsEnabled,
        int criticalAlertEscalationMinutes,
        String criticalAlertRecipientEmails,
        boolean nlAssistantWriteCommandsEnabled,
        // Bistrodesk Phase 3 - see Restaurant#menuCentralized's javadoc.
        boolean menuCentralized,
        // POS patch - see Restaurant#itemLevelKitchenStatusEnabled's javadoc.
        boolean itemLevelKitchenStatusEnabled,
        long version,
        List<BranchDto> branches
) {
    public record BranchDto(UUID id, String name, String address, String phone, long version,
                             // POS patch - see Branch#manualKotPrintEnabled's javadoc. Exposed here
                             // (unlike the full com.chefpay.server.branches.BranchDto, which needs
                             // BRANCH_MANAGE) because /api/restaurant has no permission gate and is
                             // exactly what PosTerminalPage.tsx already reads to resolve its own
                             // bound branch's config.
                             boolean manualKotPrintEnabled,
                             List<FloorDto> floors) {
    }

    public record FloorDto(UUID id, String name, int displayOrder, long version) {
    }
}
