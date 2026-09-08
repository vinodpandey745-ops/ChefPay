package com.chefpay.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Lowest-level piece of Round 10's AI integration - one method call in, one HTTP request out, per
 * provider. Deliberately built on the JDK's own {@link HttpClient} + Jackson's {@link ObjectMapper}
 * (both already dependencies of every other server module) rather than any provider's official
 * Java SDK - this sandbox can't verify a brand-new Maven dependency actually resolves, and each
 * provider's chat-completion HTTP contract is small and stable enough to hand-build directly. Never
 * called from a controller - always go through {@link AiService}, which owns the three-gate
 * enablement check and exception translation this class deliberately does NOT do.
 */
@Component
public class AiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public String chatText(AiProvider provider, String apiKey, String model, String systemPrompt, String userPrompt) {
        return chat(provider, apiKey, model, systemPrompt, userPrompt, null, null);
    }

    public String chatWithImage(AiProvider provider, String apiKey, String model, String systemPrompt, String userPrompt,
                                 String base64Image, String mimeType) {
        return chat(provider, apiKey, model, systemPrompt, userPrompt, base64Image, mimeType);
    }

    private String chat(AiProvider provider, String apiKey, String model, String systemPrompt, String userPrompt,
                         String base64Image, String mimeType) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiException("No AI API key configured.");
        }
        if (provider == null) {
            throw new AiException("No AI provider selected.");
        }
        try {
            HttpRequest request = switch (provider) {
                case OPENAI -> buildOpenAiRequest(apiKey, model, systemPrompt, userPrompt, base64Image, mimeType);
                case ANTHROPIC -> buildAnthropicRequest(apiKey, model, systemPrompt, userPrompt, base64Image, mimeType);
                case GEMINI -> buildGeminiRequest(apiKey, model, systemPrompt, userPrompt, base64Image, mimeType);
            };
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiException("The AI provider rejected the request (HTTP " + response.statusCode()
                        + "): " + truncate(response.body()));
            }
            return switch (provider) {
                case OPENAI -> extractOpenAiText(response.body());
                case ANTHROPIC -> extractAnthropicText(response.body());
                case GEMINI -> extractGeminiText(response.body());
            };
        } catch (AiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiException("Failed to reach the AI provider: " + ex.getMessage(), ex);
        }
    }

    // ---- OpenAI: POST https://api.openai.com/v1/chat/completions ----

    private HttpRequest buildOpenAiRequest(String apiKey, String model, String systemPrompt, String userPrompt,
                                            String base64Image, String mimeType) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model == null || model.isBlank() ? "gpt-4o-mini" : model);
        root.put("temperature", 0.3);
        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        if (base64Image != null) {
            ArrayNode content = user.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", userPrompt);
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            imagePart.putObject("image_url")
                    .put("url", "data:" + (mimeType == null ? "image/png" : mimeType) + ";base64," + base64Image);
        } else {
            user.put("content", userPrompt);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
    }

    private String extractOpenAiText(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AiException("The AI provider's response had no choices.");
        }
        return choices.get(0).path("message").path("content").asText("");
    }

    // ---- Anthropic: POST https://api.anthropic.com/v1/messages ----

    private HttpRequest buildAnthropicRequest(String apiKey, String model, String systemPrompt, String userPrompt,
                                               String base64Image, String mimeType) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model == null || model.isBlank() ? "claude-3-5-sonnet-20241022" : model);
        root.put("max_tokens", 2048);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("system", systemPrompt);
        }
        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        if (base64Image != null) {
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image");
            ObjectNode source = imagePart.putObject("source");
            source.put("type", "base64");
            source.put("media_type", mimeType == null ? "image/png" : mimeType);
            source.put("data", base64Image);
        }
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", userPrompt);
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
    }

    private String extractAnthropicText(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new AiException("The AI provider's response had no content.");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    // ---- Gemini: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key=... ----

    private HttpRequest buildGeminiRequest(String apiKey, String model, String systemPrompt, String userPrompt,
                                            String base64Image, String mimeType) {
        String effectiveModel = model == null || model.isBlank() ? "gemini-1.5-flash" : model;
        ObjectNode root = MAPPER.createObjectNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);
        }
        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", userPrompt);
        if (base64Image != null) {
            ObjectNode inlineData = parts.addObject().putObject("inlineData");
            inlineData.put("mimeType", mimeType == null ? "image/png" : mimeType);
            inlineData.put("data", base64Image);
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + effectiveModel
                + ":generateContent?key=" + apiKey;
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();
    }

    private String extractGeminiText(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new AiException("The AI provider's response had no candidates.");
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            sb.append(part.path("text").asText(""));
        }
        return sb.toString();
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
