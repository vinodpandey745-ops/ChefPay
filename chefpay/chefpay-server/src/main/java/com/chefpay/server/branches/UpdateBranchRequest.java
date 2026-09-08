package com.chefpay.server.branches;

/** Any null field (other than version) leaves that attribute unchanged, same convention as every
 * other update-request record in this codebase (see {@code UpdateRestaurantRequest}'s javadoc).
 * {@code active=false} is the "deactivate" action (item 6's "safe deactivation, not blind
 * hard-delete") - see {@code BranchController#update}'s javadoc for why this is blocked while the
 * branch still has active terminals.
 *
 * <p>Bistrodesk branch-isolation release (requirement #4): gstin/supportPhone/receiptFooterText/
 * logoImageBase64 are this branch's own restaurant-profile fields - an empty string clears a
 * previously-set value (same convention {@code UpdateRestaurantRequest#logoImageBase64} already
 * uses for "Remove Logo"), null leaves it unchanged.
 *
 * <p>Follow-up requirement #4 (WhatsApp integration): whatsappProvider/whatsappSenderNumber/
 * whatsappApiKey/whatsappAccountId configure this branch's WhatsApp Business API sender - same
 * empty-string-clears/null-means-unchanged convention, and {@code whatsappApiKey} is write-only
 * (never echoed back, same as {@code UpdateRestaurantRequest#aiApiKey}/{@code smtpPassword}) - see
 * {@code BranchDto#whatsappApiKeyConfigured} for how the client learns whether one is set.
 *
 * <p>Follow-up enhancement ("Local Time Zone During Branch Creation"): {@code timezone} is an IANA
 * zone id (e.g. {@code "Asia/Kolkata"}) - null leaves the branch's current zone unchanged, same
 * convention as every other field here (a branch's timezone is never intentionally cleared back to
 * blank the way a logo/footer can be - the client's zone picker always sends a real selection). */
public record UpdateBranchRequest(
        String name,
        String address,
        String phone,
        Boolean active,
        long version,
        String gstin,
        String supportPhone,
        String receiptFooterText,
        String logoImageBase64,
        String whatsappProvider,
        String whatsappSenderNumber,
        String whatsappApiKey,
        String whatsappAccountId,
        String timezone,
        // POS patch - see Branch#manualKotPrintEnabled's javadoc. Null = unchanged.
        Boolean manualKotPrintEnabled
) {
}
