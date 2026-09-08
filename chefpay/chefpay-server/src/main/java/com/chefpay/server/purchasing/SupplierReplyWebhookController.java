package com.chefpay.server.purchasing;

import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Bistrodesk Phase 9 (requirement #19-22): the "supplier replied" auto-ack + forward-to-owner
 * requirement. There is no real WhatsApp Business API account/webhook subscription anywhere in
 * this project (confirmed - same gap {@link SupplierChannel}'s javadoc documents for the outbound
 * side), so this endpoint genuinely cannot receive anything from an actual supplier's WhatsApp
 * today; it exists so a real provider integration has a working shape to point at, and so the
 * "auto-ack + forward-to-owner" logic ({@link PurchaseOrderService#handleInboundSupplierReply}) is
 * already built, tested against, and ready the moment a real webhook subscription exists.
 *
 * <p>Deliberately a standalone {@code @RestController} at {@code /webhooks/whatsapp} rather than a
 * method on {@link PurchaseOrderController} for two reasons: (1) that controller's class-level
 * {@code @RequiresFeature} resolves a branch from the CALLER's own JWT/query param, neither of
 * which a provider webhook has - not a check that means anything for an inbound delivery with no
 * branch of its own; (2) {@code /api/**} sits behind this app's normal JWT filter, and a provider
 * has no Bistrodesk JWT to present - see {@code SecurityConfig}'s {@code /webhooks/**} permitAll
 * entry, which mirrors {@code /platform/**}'s exact same "public path, gated by its own secret
 * instead" shape.
 *
 * <p>Security: every request must present {@code chefpay.whatsapp.webhook-secret} (see that
 * property's own comment in {@code application.yml}) via {@code X-Webhook-Secret} - constant-time
 * compared, refusing everything if the property is unset, identical to {@code
 * PlatformOwnerController#requireValidKey}. Deliberately provider-agnostic rather than implementing
 * one specific vendor's real signature scheme (e.g. Twilio's HMAC-signed {@code X-Twilio-Signature},
 * or Meta's {@code hub.verify_token} GET handshake) - without real provider credentials to build
 * and test against, implementing one vendor's exact verification would be unverifiable, untested
 * code masquerading as security; a real integration should replace this header check with that
 * provider's actual verification when it's built, using whichever value the provider lets an
 * operator configure as the shared secret in the interim.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
@RequiredArgsConstructor
public class SupplierReplyWebhookController {

    private final PurchaseOrderService purchaseOrderService;

    @Value("${chefpay.whatsapp.webhook-secret:}")
    private String configuredSecret;

    @PostMapping("/supplier-reply")
    public ApiResponse<PurchaseOrderDtos.InboundSupplierReplyResult> supplierReply(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String providedSecret,
            @Valid @RequestBody PurchaseOrderDtos.InboundSupplierReplyRequest request) {
        requireValidSecret(providedSecret);
        return ApiResponse.ok(purchaseOrderService.handleInboundSupplierReply(request.from(), request.text()));
    }

    /** Constant-time comparison (never {@code String#equals}) against the configured secret - same
     * reasoning and same refuse-if-unconfigured behavior as {@code
     * PlatformOwnerController#requireValidKey}'s identical javadoc. */
    private void requireValidSecret(String providedSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw ApiException.forbidden(
                    "The WhatsApp inbound webhook is not configured on this install (CHEFPAY_WHATSAPP_WEBHOOK_SECRET is unset).");
        }
        if (providedSecret == null || providedSecret.isBlank()
                || !MessageDigest.isEqual(providedSecret.getBytes(StandardCharsets.UTF_8), configuredSecret.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("Invalid webhook secret.");
        }
    }
}
