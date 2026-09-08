package com.chefpay.server.purchasing.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Lowest-level piece of the branch-isolation release's WhatsApp integration (requirement #4) - one
 * method call in, one HTTP request out, per provider. Deliberately built on the JDK's own {@link
 * HttpClient} + Jackson's {@link ObjectMapper} (both already dependencies of every other server
 * module) rather than any provider's official Java SDK, mirroring {@code
 * com.chefpay.server.ai.AiClient}'s exact reasoning: this sandbox can't verify a brand-new Maven
 * dependency actually resolves, and each provider's send-a-text-message HTTP contract is small and
 * stable enough to hand-build directly against its published REST API. Never called from a
 * controller - always go through {@link WhatsAppService}, which owns the "is this branch actually
 * configured" gate and exception translation this class deliberately does NOT do.
 *
 * <p>Every provider here sends a plain text message to one recipient and returns nothing this
 * class needs back except success/failure - none of the three richer feature sets (templates,
 * media, interactive buttons) are wired up, matching the one thing requirement #4 actually asks
 * for ("share an approved PO... via whatsapp").
 */
@Component
public class WhatsAppClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public void sendText(WhatsAppProvider provider, String apiKey, String accountId, String fromNumber, String toNumber, String message) {
        if (provider == null) {
            throw new WhatsAppException("No WhatsApp provider selected.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new WhatsAppException("No WhatsApp API credential configured.");
        }
        try {
            HttpRequest request = switch (provider) {
                case META -> buildMetaRequest(apiKey, accountId, toNumber, message);
                case TWILIO -> buildTwilioRequest(apiKey, accountId, fromNumber, toNumber, message);
                case DIALOG360 -> buildDialog360Request(apiKey, toNumber, message);
            };
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WhatsAppException("The WhatsApp provider rejected the request (HTTP " + response.statusCode()
                        + "): " + truncate(response.body()));
            }
        } catch (WhatsAppException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new WhatsAppException("Failed to reach the WhatsApp provider: " + ex.getMessage(), ex);
        }
    }

    // ---- Meta (WhatsApp Cloud API): POST https://graph.facebook.com/v20.0/{phone-number-id}/messages ----
    // Docs: https://developers.facebook.com/docs/whatsapp/cloud-api/reference/messages
    // apiKey = permanent/system-user access token; accountId = the WhatsApp phone number ID (NOT the
    // phone number itself - Meta identifies the sender by this opaque ID, assigned when the number
    // is registered in Meta Business Manager).

    private HttpRequest buildMetaRequest(String apiKey, String phoneNumberId, String toNumber, String message) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            throw new WhatsAppException("No WhatsApp phone number ID configured for the Meta Cloud API.");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("messaging_product", "whatsapp");
        root.put("to", digitsOnly(toNumber));
        root.put("type", "text");
        ObjectNode text = root.putObject("text");
        text.put("body", message);
        return HttpRequest.newBuilder()
                .uri(URI.create("https://graph.facebook.com/v20.0/" + phoneNumberId.trim() + "/messages"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
    }

    // ---- Twilio: POST https://api.twilio.com/2010-04-01/Accounts/{AccountSid}/Messages.json ----
    // Docs: https://www.twilio.com/docs/whatsapp/api
    // apiKey = Auth Token; accountId = Account SID; fromNumber = the branch's Twilio-approved
    // WhatsApp sender (E.164) - Twilio requires both the From and To addresses prefixed "whatsapp:".

    private HttpRequest buildTwilioRequest(String authToken, String accountSid, String fromNumber, String toNumber, String message) {
        if (accountSid == null || accountSid.isBlank()) {
            throw new WhatsAppException("No Twilio Account SID configured.");
        }
        if (fromNumber == null || fromNumber.isBlank()) {
            throw new WhatsAppException("No WhatsApp sender number configured for this branch.");
        }
        String form = "From=" + urlEncode("whatsapp:" + normalizePhone(fromNumber))
                + "&To=" + urlEncode("whatsapp:" + normalizePhone(toNumber))
                + "&Body=" + urlEncode(message);
        String basicAuth = Base64.getEncoder().encodeToString((accountSid.trim() + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + accountSid.trim() + "/Messages.json"))
                .header("Authorization", "Basic " + basicAuth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
    }

    // ---- 360dialog (Cloud API-compatible v2): POST https://waba-v2.360dialog.io/messages ----
    // Docs: https://docs.360dialog.com/whatsapp-api/whatsapp-api/media-and-messages
    // apiKey = the channel's D360-API-KEY (already channel-scoped, so no separate account ID is
    // required in the request itself - Branch#whatsappAccountId is kept purely for the owner's own
    // reference on this provider, e.g. to note which channel/WABA the key belongs to).

    private HttpRequest buildDialog360Request(String apiKey, String toNumber, String message) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("messaging_product", "whatsapp");
        root.put("to", digitsOnly(toNumber));
        root.put("type", "text");
        ObjectNode text = root.putObject("text");
        text.put("body", message);
        return HttpRequest.newBuilder()
                .uri(URI.create("https://waba-v2.360dialog.io/messages"))
                .header("D360-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
    }

    /** wa.me/Cloud-API style: digits only, no leading '+'. */
    private String digitsOnly(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }

    /** Twilio wants a leading '+' - unlike the Cloud-API-style providers above. */
    private String normalizePhone(String phone) {
        String digits = digitsOnly(phone);
        return "+" + digits;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }
}
