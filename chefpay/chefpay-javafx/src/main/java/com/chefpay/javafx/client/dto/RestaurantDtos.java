package com.chefpay.javafx.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Mirrors {@code com.chefpay.server.restaurant.RestaurantDto} - used by the new Table Setup
 * screen to find a floor id to create a table under (a table always belongs to a floor). */
public final class RestaurantDtos {

    private RestaurantDtos() {
    }

    public record RestaurantDto(UUID id, String name, String currencySymbol, String defaultTimezone, String gstin,
                                 String supportPhone, BigDecimal serviceChargePercent,
                                 boolean requireKitchenSyncForServed, String receiptFooterText,
                                 boolean onlineOrderZomatoEnabled, boolean onlineOrderSwiggyEnabled,
                                 String enabledPaymentMethods, String upiVpaId, String upiPayeeName,
                                 boolean cardPaymentEnabled, String cardTerminalNote, boolean cashDrawerEnabled,
                                 String receiptPrinterName, int receiptPaperWidthChars, boolean autoPrintOnlineOrders,
                                 boolean autoPrintReceiptOnPayment,
                                 boolean deliveryBoyFeatureEnabled,
                                 boolean kotOptionalEnabled,
                                 String logoImageBase64,
                                 String smtpHost, Integer smtpPort, String smtpUsername, boolean smtpPasswordConfigured,
                                 String smtpFromAddress, boolean smtpUseTls,
                                 boolean aiFeaturesEnabled, String aiProvider, String aiModel, boolean aiApiKeyConfigured,
                                 boolean aiMenuImportEnabled, boolean aiInsightsChatEnabled, boolean aiReorderDraftsEnabled,
                                 boolean aiAnomalyFlaggingEnabled, boolean aiMenuDescriptionsEnabled, boolean aiNightlySummaryEnabled,
                                 String dashboardViewMode, String kitchenServiceMode, boolean showDiscountConfirmation,
                                 boolean poApprovalRequired, boolean aiReplenishmentNotesEnabled,
                                 boolean ocrUseAiVisionAssist, boolean biometricOverrideEnabled,
                                 boolean autoPurgeEnabled, int dataRetentionDays,
                                 long version, List<BranchDto> branches) {
    }

    public record BranchDto(UUID id, String name, String address, String phone, long version, List<FloorDto> floors) {
    }

    public record FloorDto(UUID id, String name, int displayOrder, long version) {
    }

    /** Any null field (except version) leaves that attribute unchanged - mirrors the server DTO. */
    public record UpdateRestaurantRequest(String name, String currencySymbol, String defaultTimezone, String gstin,
                                           String supportPhone, BigDecimal serviceChargePercent,
                                           Boolean requireKitchenSyncForServed, String receiptFooterText,
                                           Boolean onlineOrderZomatoEnabled, Boolean onlineOrderSwiggyEnabled,
                                           String enabledPaymentMethods, String upiVpaId, String upiPayeeName,
                                           Boolean cardPaymentEnabled, String cardTerminalNote, Boolean cashDrawerEnabled,
                                           String receiptPrinterName, Integer receiptPaperWidthChars,
                                           Boolean autoPrintOnlineOrders, Boolean autoPrintReceiptOnPayment,
                                           Boolean deliveryBoyFeatureEnabled,
                                           Boolean kotOptionalEnabled,
                                           String logoImageBase64,
                                           String smtpHost, Integer smtpPort, String smtpUsername, String smtpPassword,
                                           String smtpFromAddress, Boolean smtpUseTls,
                                           Boolean aiFeaturesEnabled, String aiProvider, String aiApiKey, String aiModel,
                                           Boolean aiMenuImportEnabled, Boolean aiInsightsChatEnabled, Boolean aiReorderDraftsEnabled,
                                           Boolean aiAnomalyFlaggingEnabled, Boolean aiMenuDescriptionsEnabled, Boolean aiNightlySummaryEnabled,
                                           String dashboardViewMode, String kitchenServiceMode, Boolean showDiscountConfirmation,
                                           Boolean poApprovalRequired, Boolean aiReplenishmentNotesEnabled,
                                           Boolean ocrUseAiVisionAssist, Boolean biometricOverrideEnabled,
                                           Boolean autoPurgeEnabled, Integer dataRetentionDays,
                                           long version) {
    }
}
