package com.chefpay.server.purchasing.whatsapp;

import com.chefpay.core.domain.Branch;
import com.chefpay.server.purchasing.SupplierChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Central gate + dispatch point for the branch-isolation release's WhatsApp Business API
 * integration (requirement #4: "write a whatsapp meta, twilio, 360dialog integration and make it
 * configurable inside setting ... so that when we buy whatsappAPI it will just a config change").
 * Mirrors {@code com.chefpay.server.ai.AiService}'s shape exactly: {@link #isConfigured} is the
 * one gate every caller checks first (a branch that hasn't set a provider/credential/sender number
 * yet is simply "not configured", never a hard error), and {@link #send} is the only place that
 * reads a branch's WhatsApp credentials back out and hands them to {@link WhatsAppClient}.
 *
 * <p>Returns {@link SupplierChannel.Result} rather than a WhatsApp-specific type so {@code
 * PurchaseOrderService#shareWithSupplier} can handle a successful/failed WhatsApp send exactly the
 * same way it already handles {@code SupplierChannel}'s own {@code API} share method - one shape,
 * one place that turns a failure into a client-facing {@code ApiException}.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppService {

    private final WhatsAppClient whatsAppClient;

    /** True only when every field a real send needs is present - provider, credential, AND sender
     * number. A branch missing any one of these is "not configured", the same all-or-nothing gate
     * {@code AiService#assertEnabled} applies to {@code aiFeaturesEnabled}/{@code aiApiKey}/{@code
     * aiProvider}. */
    public boolean isConfigured(Branch branch) {
        return branch != null
                && WhatsAppProvider.fromCode(branch.getWhatsappProvider()) != null
                && notBlank(branch.getWhatsappApiKey())
                && notBlank(branch.getWhatsappSenderNumber());
    }

    /** Sends {@code message} to {@code toPhone} as this branch's WhatsApp Business sender. Callers
     * MUST check {@link #isConfigured} first - this throws {@link WhatsAppException} (translated
     * into a failed {@link SupplierChannel.Result}, never left to propagate) if called on an
     * unconfigured branch, the same defensive-but-not-the-primary-gate shape {@code AiClient#chat}
     * uses for a missing key. */
    public SupplierChannel.Result send(Branch branch, String toPhone, String message) {
        if (toPhone == null || toPhone.isBlank()) {
            return new SupplierChannel.Result(false, "No phone number to send this WhatsApp message to.");
        }
        try {
            whatsAppClient.sendText(WhatsAppProvider.fromCode(branch.getWhatsappProvider()), branch.getWhatsappApiKey(),
                    branch.getWhatsappAccountId(), branch.getWhatsappSenderNumber(), toPhone, message);
            return new SupplierChannel.Result(true, "Sent via WhatsApp Business API");
        } catch (WhatsAppException ex) {
            return new SupplierChannel.Result(false, ex.getMessage());
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
