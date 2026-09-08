package com.chefpay.server.restaurant;

import java.math.BigDecimal;

/** Any null field (except version) leaves that attribute unchanged - requireKitchenSyncForServed
 * is a Boolean (not boolean) for exactly that reason, same pattern as UpdateItemRequest.available. */
public record UpdateRestaurantRequest(
        String name,
        // Round 17: Organization/Branch/Terminal identity model - null = unchanged, same convention
        // as every other field here.
        String organizationId,
        String organizationName,
        // Phase 2: Organization profile fields - null = unchanged, same convention as every other
        // field here. Deliberately no "status" field - see RestaurantDto's javadoc for why that's
        // platform-owner-only.
        String contactEmail,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String country,
        String currencySymbol,
        String defaultTimezone,
        String gstin,
        String supportPhone,
        BigDecimal serviceChargePercent,
        Boolean requireKitchenSyncForServed,
        String receiptFooterText,
        Boolean onlineOrderZomatoEnabled,
        Boolean onlineOrderSwiggyEnabled,
        String enabledPaymentMethods,
        String upiVpaId,
        String upiPayeeName,
        Boolean cardPaymentEnabled,
        String cardTerminalNote,
        Boolean cashDrawerEnabled,
        String receiptPrinterName,
        Integer receiptPaperWidthChars,
        Boolean autoPrintOnlineOrders,
        Boolean autoPrintReceiptOnPayment,
        Boolean deliveryBoyFeatureEnabled,
        Boolean kotOptionalEnabled,
        String logoImageBase64,
        // Round 11 - email receipt delivery. Null = unchanged for every field, same convention as
        // the rest of this record. smtpPassword follows aiApiKey's "blank string clears it" rule -
        // never returned by RestaurantDto, only ever written here.
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String smtpPassword,
        String smtpFromAddress,
        Boolean smtpUseTls,
        Boolean aiFeaturesEnabled,
        String aiProvider,
        // Null = unchanged, matching every other field here. Blank string ("") clears a
        // previously-set key - same "empty string is a real value, only null means unchanged"
        // convention logoImageBase64 already uses for "Remove Logo".
        String aiApiKey,
        String aiModel,
        Boolean aiMenuImportEnabled,
        Boolean aiInsightsChatEnabled,
        Boolean aiReorderDraftsEnabled,
        Boolean aiAnomalyFlaggingEnabled,
        Boolean aiMenuDescriptionsEnabled,
        Boolean aiNightlySummaryEnabled,
        // Round 12 - dashboard/kitchen/billing workflow configuration + PO approval + AI
        // replenishment notes. Null = unchanged, same convention as every other field here.
        String dashboardViewMode,
        String kitchenServiceMode,
        Boolean showDiscountConfirmation,
        Boolean poApprovalRequired,
        Boolean aiReplenishmentNotesEnabled,
        // Final round - F2.3 / F4.5 feature flags. Null = unchanged, same convention as every
        // other field here.
        Boolean ocrUseAiVisionAssist,
        Boolean biometricOverrideEnabled,
        // Final round (post-delivery follow-up) - data-retention auto-purge master switch/window.
        // Null = unchanged, same convention as every other field here.
        Boolean autoPurgeEnabled,
        Integer dataRetentionDays,
        // Round 17 desktop-parity port - null = unchanged, same convention as every other field here.
        BigDecimal defaultOpeningFloat,
        BigDecimal cashVarianceThreshold,
        String eodZReportRecipientEmails,
        BigDecimal marginErosionThresholdPercent,
        Boolean autoPoFromSuggestionsEnabled,
        Integer criticalAlertEscalationMinutes,
        String criticalAlertRecipientEmails,
        Boolean nlAssistantWriteCommandsEnabled,
        // Bistrodesk Phase 3 - purely informational Menu Editor default (see
        // MenuItem#branch's javadoc for why this doesn't itself gate anything). Null = unchanged.
        Boolean menuCentralized,
        // POS patch - see Restaurant#itemLevelKitchenStatusEnabled's javadoc. Null = unchanged.
        Boolean itemLevelKitchenStatusEnabled,
        long version
) {
}
