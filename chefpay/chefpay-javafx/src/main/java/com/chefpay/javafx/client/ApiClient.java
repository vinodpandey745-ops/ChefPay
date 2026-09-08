package com.chefpay.javafx.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Thin REST client over chefpay-server. Deliberately dumb - it does no business logic (§61), it
 * just sends/receives JSON and unwraps the standard {@link ApiEnvelope}. UI code owns retry/error
 * presentation; ViewModels own what to call and when.
 */
public class ApiClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    // Round 19 hotfix: without this, any server DTO gaining a new field (e.g. LoginResponse's
    // Round 17 organizationId/organizationName/terminal additions) makes every JavaFX record that
    // mirrors it by hand throw "Failed to parse server response" the instant the server adds a
    // field the record doesn't yet declare - Jackson's FAIL_ON_UNKNOWN_PROPERTIES defaults to true.
    // A hand-mirrored client DTO should tolerate the server knowing more fields than it does; it
    // should only ever fail on a field it actually NEEDS being *missing*, never on an extra one.
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final String baseUrl;

    public ApiClient() {
        this(ServerConfig.httpBaseUrl());
    }

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public JsonNode get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET());
    }

    public JsonNode post(String path, Object body) {
        return send(withBody(HttpRequest.newBuilder(URI.create(baseUrl + path)), "POST", body));
    }

    public JsonNode patch(String path, Object body) {
        return send(withBody(HttpRequest.newBuilder(URI.create(baseUrl + path)), "PATCH", body));
    }

    public JsonNode put(String path, Object body) {
        return send(withBody(HttpRequest.newBuilder(URI.create(baseUrl + path)), "PUT", body));
    }

    public JsonNode delete(String path, Object body) {
        return send(withBody(HttpRequest.newBuilder(URI.create(baseUrl + path)), "DELETE", body));
    }

    private HttpRequest.Builder withBody(HttpRequest.Builder builder, String method, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            return builder.timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        } catch (IOException e) {
            throw new ApiException("Failed to serialize request body", e);
        }
    }

    private JsonNode send(HttpRequest.Builder builder) {
        String token = SessionStore.get().getToken();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        String correlationId = UUID.randomUUID().toString();
        builder.header("X-Correlation-Id", correlationId);

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            ApiEnvelope envelope = mapper.readValue(response.body(), ApiEnvelope.class);
            if (!envelope.success()) {
                throw new ApiException(envelope.errorCode(), envelope.message());
            }
            return envelope.data();
        } catch (IOException e) {
            throw new ApiException("Could not reach Bistrodesk server at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    /** Round 13: raw binary GET for endpoints that don't return the standard {@link ApiEnvelope}
     * JSON envelope - currently just the EOD Z-Report PDF download
     * ({@code EodController#zReportPdf}). Bypasses {@link #send} entirely (that method always tries
     * to parse the body as an {@link ApiEnvelope}, which would fail on raw PDF bytes) and throws
     * {@link ApiException} on any non-2xx status or transport failure, same contract as every other
     * method here. */
    public byte[] getBytes(String path) {
        String token = SessionStore.get().getToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException("HTTP_" + response.statusCode(), "Request failed with status " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new ApiException("Could not reach Bistrodesk server at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", e);
        }
    }

    /** Round 11: lightweight reachability probe for {@link SyncEngine}'s online/offline detection.
     * Hits {@code /actuator/health} directly with its own raw request rather than going through
     * {@link #send}, since that endpoint returns Spring Boot's own {@code {"status":"UP"}} shape,
     * not this app's {@link ApiEnvelope} contract - {@link #send} would throw trying to parse it.
     * Only the HTTP status matters here, not the body. */
    public boolean pingHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Round 11: parses a JSON string previously returned by {@link #get} (typically one read back
     * from {@link LocalDatabase}'s offline cache) into the same {@link JsonNode} shape {@link #get}
     * itself returns, so {@link #convert}/{@link #convertList} work identically whether the data
     * came from a live call or the local cache. */
    public JsonNode parseCached(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new ApiException("Failed to parse cached response", e);
        }
    }

    public <T> T convert(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (IOException e) {
            throw new ApiException("Failed to parse server response", e);
        }
    }

    public <T> java.util.List<T> convertList(JsonNode node, Class<T> elementType) {
        try {
            var listType = mapper.getTypeFactory().constructCollectionType(java.util.List.class, elementType);
            return mapper.readValue(mapper.treeAsTokens(node), listType);
        } catch (IOException e) {
            throw new ApiException("Failed to parse server response list", e);
        }
    }
}
