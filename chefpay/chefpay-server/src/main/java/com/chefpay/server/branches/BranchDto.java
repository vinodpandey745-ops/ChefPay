package com.chefpay.server.branches;

import java.util.UUID;

/** Phase 2: the Manager/Admin "Branches" screen's row shape - see {@code Branch}'s javadoc for what
 * {@code branchCode} is and why it's system-generated rather than free-text. {@code terminalCount}
 * is shown so an admin can tell at a glance whether a branch is safe to hard-delete (0 terminals).
 *
 * <p>Bistrodesk branch-isolation release (requirement #4): gstin/supportPhone/receiptFooterText/
 * logoImageBase64 are this branch's own restaurant-profile fields (see {@code Branch}'s javadoc) -
 * same shape {@code RestaurantDto} already uses for the install-wide equivalents.
 *
 * <p>Follow-up requirement #4 (WhatsApp integration): whatsappProvider/whatsappSenderNumber/
 * whatsappAccountId are this branch's WhatsApp Business API config (see {@code Branch}'s javadoc) -
 * {@code whatsappApiKeyConfigured} masks the raw credential exactly like {@code
 * RestaurantDto#aiApiKeyConfigured} masks {@code aiApiKey} - the client only ever needs to know
 * whether a key is set, never its value.
 *
 * <p>Follow-up enhancement ("Local Time Zone During Branch Creation"): {@code timezone} is this
 * branch's own IANA zone id (see {@code Branch#getTimezone()}'s javadoc) - always populated (never
 * null) once {@code V44}/{@code DataSeeder#ensureBranchTimezoneBackfill} has run. */
public record BranchDto(
        UUID id,
        String name,
        String branchCode,
        String address,
        String phone,
        boolean active,
        long terminalCount,
        long version,
        String gstin,
        String supportPhone,
        String receiptFooterText,
        String logoImageBase64,
        String whatsappProvider,
        String whatsappSenderNumber,
        String whatsappAccountId,
        boolean whatsappApiKeyConfigured,
        String timezone,
        // POS patch - see Branch#manualKotPrintEnabled's javadoc.
        boolean manualKotPrintEnabled
) {
}
