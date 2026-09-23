package com.meterly.validation.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.meterly.validation.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Thin JSON HTTP client over the JDK's {@link HttpClient} (no Spring, per docs/why-java.md §9). Never
 * throws on a non-2xx status: every call returns an {@link ApiResponse} so checks can assert on 404s,
 * 409s and 429s as first-class outcomes rather than catching exceptions. Only transport failures
 * (connection refused, timeout) throw, wrapped as an unchecked {@link HarnessException}.
 */
public final class ApiClient {

    private final HttpClient client;
    private final URI baseUri;

    public ApiClient(String baseUrl) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public HttpClient rawClient() {
        return client;
    }

    public URI resolve(String path) {
        return baseUri.resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    public <T> ApiResponse<T> get(String path, Auth auth, Class<T> type) {
        return send("GET", path, auth, null, type, null);
    }

    public <T> ApiResponse<T> get(String path, Auth auth, TypeReference<T> type) {
        return send("GET", path, auth, null, null, type);
    }

    public <T> ApiResponse<T> post(String path, Auth auth, Object body, Class<T> type) {
        return send("POST", path, auth, body, type, null);
    }

    public <T> ApiResponse<T> post(String path, Auth auth, Object body, TypeReference<T> type) {
        return send("POST", path, auth, body, null, type);
    }

    public <T> ApiResponse<T> put(String path, Auth auth, Object body, Class<T> type) {
        return send("PUT", path, auth, body, type, null);
    }

    public ApiResponse<Map<String, Object>> delete(String path, Auth auth) {
        return send("DELETE", path, auth, null, null, new TypeReference<Map<String, Object>>() {
        });
    }

    private <T> ApiResponse<T> send(String method, String path, Auth auth, Object jsonBody,
            Class<T> type, TypeReference<T> typeRef) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json");
            switch (auth) {
                case Auth.Bearer b -> builder.header("Authorization", "Bearer " + b.jwt());
                case Auth.ApiKey k -> builder.header("X-API-Key", k.key());
                case Auth.None ignored -> {
                }
            }
            HttpRequest.BodyPublisher publisher = jsonBody == null
                    ? BodyPublishers.noBody()
                    : BodyPublishers.ofString(Json.mapper().writeValueAsString(jsonBody));
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json");
            }
            builder.method(method, publisher);
            HttpResponse<String> response = client.send(builder.build(), BodyHandlers.ofString());
            String raw = response.body();
            T parsed = null;
            if (raw != null && !raw.isBlank()) {
                try {
                    parsed = type != null ? Json.mapper().readValue(raw, type) : Json.mapper().readValue(raw, typeRef);
                } catch (IOException ignoredParseFailure) {
                    // Error responses (problem+json) or an unexpected shape; callers fall back to rawBody().
                }
            }
            return new ApiResponse<>(response.statusCode(), response.headers(), parsed, raw);
        } catch (IOException e) {
            throw new HarnessException(method + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException(method + " " + path + " interrupted", e);
        }
    }
}
