package com.chefpay.plugin.api;

import com.chefpay.plugin.api.dto.BillDto;
import com.chefpay.plugin.api.dto.CustomerDto;
import com.chefpay.plugin.api.dto.NotificationResult;

/**
 * Extension point for sending the closed bill / receipt to the customer over some
 * external channel - WhatsApp, SMS, email, etc. Entirely optional: if no enabled
 * plugin implements this, the billing screen simply doesn't show a "Send" button
 * for that channel.
 *
 * <p>Called by core after an order is billed and closed (see OrderService#closeBill).
 * Multiple BillNotifier plugins may be enabled simultaneously (e.g. WhatsApp + SMS).
 */
public interface BillNotifier extends ChefPayPlugin {

    /**
     * @param bill         the finalized bill
     * @param customer     customer info (name/phone may be blank for anonymous walk-ins -
     *                     implementations should return a failure NotificationResult rather
     *                     than throwing if required contact info is missing)
     * @param receiptPdf   rendered receipt as a PDF byte array, ready to attach/send
     */
    NotificationResult sendBill(BillDto bill, CustomerDto customer, byte[] receiptPdf);

    /** Short label for the button shown on the billing screen, e.g. "Send via WhatsApp". */
    default String getActionLabel() {
        return "Send via " + getName();
    }
}
