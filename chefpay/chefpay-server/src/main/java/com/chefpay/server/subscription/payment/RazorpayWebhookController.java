package com.chefpay.server.subscription.payment;

import com.chefpay.server.common.ApiException;
import com.chefpay.server.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription Renewal and Plan Upgrade requirement: a durability backstop ONLY - covers the case
 * where the browser tab closed (network drop, phone locked, app killed) after Razorpay actually
 * captured the payment but before the frontend's own {@code POST /api/subscription/renew/verify}
 * call fired, which would otherwise leave a genuinely-paid {@code SubscriptionPayment} row stuck
 * at {@code CREATED} forever. This has NOT been exercised against real Razorpay production
 * traffic (no live account exists in this sandbox) - same honest limitation {@code
 * SupplierReplyWebhookController}'s javadoc documents for its own inbound-webhook shape: the
 * request/signature handling below follows Razorpay's published webhook contract exactly (a raw-
 * body HMAC-SHA256 over {@code razorpay.webhook-secret}, header {@code X-Razorpay-Signature}), but
 * an operator wiring up a real Razorpay webhook subscription should watch this endpoint's logs the
 * first time real traffic hits it.
 *
 * <p>Deliberately a standalone {@code @RestController} at {@code /webhooks/razorpay}, not a method
 * on {@link com.chefpay.server.subscription.SubscriptionController} - same two reasons {@code
 * SupplierReplyWebhookController}'s javadoc gives for its own placement: (1) Razorpay's webhook
 * call has no Bistrodesk JWT/branch context of its own to resolve a branch from; (2) {@code
 * /webhooks/**} is already {@code permitAll} in {@code SecurityConfig}, gated by this endpoint's
 * own signature check instead of the normal Bearer-token filter.
 *
 * <p>{@code @RequestBody String rawBody} (not a parsed DTO) is deliberate: Razorpay signs the
 * EXACT bytes it sent, and letting Spring bind straight to a DTO would parse-then-reserialize the
 * body, which is not guaranteed to reproduce byte-for-byte the same string the signature was
 * computed over - see {@link RazorpayClient#verifyWebhookSignature}'s own javadoc. The body is
 * parsed with Jackson by hand only AFTER the signature has already been verified.
 */
@RestController
@RequestMapping("/webhooks/razorpay")
@RequiredArgsConstructor
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RazorpayClient razorpayClient;
    private final SubscriptionRenewalService subscriptionRenewalService;

    @PostMapping
    public ApiResponse<Void> handle(@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                     @RequestBody(required = false) String rawBody) {
        boolean verified;
        try {
            verified = razorpayClient.verifyWebhookSignature(subscriptionRenewalService.webhookSecret(), rawBody, signature);
        } catch (RazorpayException ex) {
            // Not configured at all - same "refuse everything" posture as PlatformOwnerController
            // #requireValidKey/SupplierReplyWebhookController#requireValidSecret for an unset secret.
            throw new ApiException("WEBHOOK_NOT_CONFIGURED", ex.getMessage(), HttpStatus.FORBIDDEN);
        }
        if (!verified) {
            throw ApiException.unauthorized("Invalid Razorpay webhook signature.");
        }

        try {
            JsonNode root = MAPPER.readTree(rawBody);
            if ("payment.captured".equals(root.path("event").asText(""))) {
                JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
                String orderId = paymentEntity.path("order_id").asText(null);
                String paymentId = paymentEntity.path("id").asText(null);
                if (orderId != null && paymentId != null) {
                    subscriptionRenewalService.activateFromWebhook(orderId, paymentId);
                }
            }
        } catch (Exception ex) {
            // A malformed/unexpected payload from an already-signature-verified sender is worth a
            // log line, but must never turn into a 5xx here - Razorpay retries a non-2xx response
            // indefinitely, and retrying an unparseable payload would never succeed differently.
            log.warn("Failed to process Razorpay webhook payload: {}", ex.getMessage());
        }
        return ApiResponse.ok(null);
    }
}
