package com.chefpay.server.subscription.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Subscription Renewal and Plan Upgrade requirement, restaurant-owner-confirmed gateway choice
 * (Razorpay - native UPI + Card support for this India-focused market). Lowest-level piece: one
 * method call in, one HTTP request out, per Razorpay REST API operation - deliberately built on
 * the JDK's own {@link HttpClient} + Jackson's {@link ObjectMapper} (both already dependencies of
 * every other server module) rather than Razorpay's official Java SDK, mirroring {@code
 * com.chefpay.server.purchasing.whatsapp.WhatsAppClient}'s exact reasoning: this sandbox can't
 * verify a brand-new Maven dependency actually resolves, and Razorpay's Orders API + webhook/
 * checkout signature scheme is a single, well-known, stable, publicly documented vendor contract
 * small enough to hand-build directly against.
 *
 * <p>Credentials are passed in as parameters on every call (never read from a static field) -
 * {@code key-id}/{@code key-secret} are wired from {@code application.yml}'s {@code
 * razorpay.key-id}/{@code razorpay.key-secret} (both blank by default, install-wide - see that
 * config block's own comment). Every method throws {@link RazorpayException} the moment a
 * credential is blank/unconfigured, or the HTTP call fails/returns non-2xx - this is deliberately
 * honest about needing a real Razorpay account rather than silently no-op-ing or faking success,
 * same precedent {@code SupplierReplyWebhookController}'s javadoc calls out for exactly this
 * situation (no real provider integrated yet).
 */
@Component
public class RazorpayClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Result of {@link #createOrder}: the Razorpay-assigned order id (looks like {@code
     * order_xxxxx}) and the amount actually sent, in paise - both are what the frontend's Razorpay
     * Checkout widget needs to open the payment sheet. */
    public record OrderResult(String orderId, long amountPaise) {
    }

    /** Creates a Razorpay Order - Docs: {@code POST https://api.razorpay.com/v1/orders}. {@code
     * amountRupees} is converted to paise (rupees * 100) as Razorpay's API requires an integer
     * amount in the smallest currency unit; {@code receipt} is this install's own reference
     * (the {@code SubscriptionPayment} row's id) so a Razorpay dashboard lookup can be traced back
     * to the exact row here. {@code payment_capture=1} means Razorpay auto-captures a successful
     * authorization immediately - simplest correct behavior for a subscription charge with no
     * separate "capture later" workflow in this feature. */
    public OrderResult createOrder(String keyId, String keySecret, java.math.BigDecimal amountRupees,
                                    String currency, String receipt) {
        requireCredentials(keyId, keySecret);
        long amountPaise = amountRupees.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("amount", amountPaise);
        root.put("currency", currency);
        root.put("receipt", receipt);
        root.put("payment_capture", 1);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.razorpay.com/v1/orders"))
                .header("Authorization", "Basic " + basicAuth(keyId, keySecret))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
        JsonNode response = send(request);
        String orderId = response.path("id").asText(null);
        if (orderId == null || orderId.isBlank()) {
            throw new RazorpayException("Razorpay did not return an order id: " + response);
        }
        return new OrderResult(orderId, amountPaise);
    }

    /** Verifies Razorpay Checkout's client-side success callback (or, equivalently, a {@code
     * payment.captured} webhook payload) genuinely came from Razorpay for THIS order/payment pair -
     * Docs: {@code HMAC_SHA256(order_id + "|" + payment_id, key_secret)} must equal {@code
     * razorpay_signature}. Constant-time compared ({@link MessageDigest#isEqual}, never {@code
     * String#equals}, which short-circuits on the first mismatched byte - a timing side-channel
     * for a check this security-critical, same discipline {@code
     * PlatformOwnerController#requireValidKey} already applies to its own secret comparison). This
     * is the single most important correctness/security property of the whole renewal feature: the
     * {@link com.chefpay.core.domain.Subscription} must never change unless this returns true. */
    public boolean verifyPaymentSignature(String keySecret, String razorpayOrderId, String razorpayPaymentId,
                                           String razorpaySignature) {
        if (keySecret == null || keySecret.isBlank()) {
            throw new RazorpayException("Payment gateway not configured - set razorpay.key-id and razorpay.key-secret");
        }
        if (razorpayOrderId == null || razorpayPaymentId == null || razorpaySignature == null) {
            return false;
        }
        String expectedHex = hmacSha256Hex(keySecret, razorpayOrderId + "|" + razorpayPaymentId);
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                razorpaySignature.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Verifies a Razorpay webhook's {@code X-Razorpay-Signature} header - Docs: {@code
     * HMAC_SHA256(raw request body, webhook secret)}, same constant-time-compare discipline as
     * {@link #verifyPaymentSignature}. Deliberately takes the raw request body bytes/string rather
     * than a re-serialized DTO - Razorpay signs the exact bytes it sent, and re-serializing a
     * parsed-then-rebuilt JSON body is not guaranteed to reproduce byte-for-byte the same string. */
    public boolean verifyWebhookSignature(String webhookSecret, String rawBody, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new RazorpayException("Payment gateway webhook not configured - set razorpay.webhook-secret");
        }
        if (rawBody == null || signatureHeader == null) {
            return false;
        }
        String expectedHex = hmacSha256Hex(webhookSecret, rawBody);
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (Exception ex) {
            throw new RazorpayException("Failed to compute the Razorpay signature: " + ex.getMessage(), ex);
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RazorpayException("Razorpay rejected the request (HTTP " + response.statusCode()
                        + "): " + truncate(response.body()));
            }
            return MAPPER.readTree(response.body());
        } catch (RazorpayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RazorpayException("Failed to reach Razorpay: " + ex.getMessage(), ex);
        }
    }

    private void requireCredentials(String keyId, String keySecret) {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new RazorpayException("Payment gateway not configured - set razorpay.key-id and razorpay.key-secret");
        }
    }

    private String basicAuth(String keyId, String keySecret) {
        return Base64.getEncoder().encodeToString((keyId.trim() + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
