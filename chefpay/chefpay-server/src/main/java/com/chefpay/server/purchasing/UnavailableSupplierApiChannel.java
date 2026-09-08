package com.chefpay.server.purchasing;

import com.chefpay.core.domain.PurchaseOrder;
import org.springframework.stereotype.Component;

/**
 * The only {@link SupplierChannel} implementation that exists today - see that interface's
 * javadoc for the full picture. ChefPay has no real supplier API account/credentials to send
 * through (identical honesty to {@code WhatsAppSender}'s "no WhatsApp Business API" note), so
 * this always reports failure with a clear, actionable message rather than silently pretending to
 * send - a fake "sent" here would be actively worse than an error, since a manager might assume
 * the supplier actually received the order and never fall back to Print/Email/WhatsApp.
 */
@Component
public class UnavailableSupplierApiChannel implements SupplierChannel {

    @Override
    public Result send(PurchaseOrder purchaseOrder, String recipient, String documentText) {
        return new Result(false, "No supplier API integration is configured yet for "
                + purchaseOrder.getSupplier().getName() + " - use Print, Email, or WhatsApp instead.");
    }
}
