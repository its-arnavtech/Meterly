package com.meterly.ingestion.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal stand-in for tenant-service's internal API, built on the JDK's own
 * {@link com.sun.net.httpserver.HttpServer} (no Spring, no extra dependency) so ingestion-service's
 * integration tests can control API-key verification and tenant-resource lookups directly, including
 * simulating an unreachable tenant-service and a resource that appears only after a cache refresh.
 */
public final class FakeTenantService {

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ApiKeyRecord> apiKeysByRawKey = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> resourcesByTenant = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> verifyCallsByKey = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> resourceCallsByTenant = new ConcurrentHashMap<>();
    private volatile boolean down = false;

    public FakeTenantService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/api-keys/verify", this::handleVerify);
        server.createContext("/internal/v1/tenants/", this::handleResources);
        server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void registerApiKey(String rawApiKey, UUID tenantId, UUID apiKeyId, Set<UUID> resourceIds) {
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        ids.addAll(resourceIds);
        apiKeysByRawKey.put(rawApiKey, new ApiKeyRecord(tenantId, apiKeyId, ids));
    }

    /** Simulates a resource being created after the key was first verified/cached. */
    public void addResourceToApiKey(String rawApiKey, UUID resourceId) {
        ApiKeyRecord record = apiKeysByRawKey.get(rawApiKey);
        if (record != null) {
            record.resourceIds().add(resourceId);
        }
    }

    public void setTenantResources(UUID tenantId, Set<UUID> resourceIds) {
        resourcesByTenant.put(tenantId, ConcurrentHashMap.newKeySet());
        resourcesByTenant.get(tenantId).addAll(resourceIds);
    }

    public void addTenantResource(UUID tenantId, UUID resourceId) {
        resourcesByTenant.computeIfAbsent(tenantId, id -> ConcurrentHashMap.newKeySet()).add(resourceId);
    }

    /** Every internal call starts failing with 500 (mapped by ingestion-service to 503 AUTH_UNAVAILABLE)
     * until this is cleared. */
    public void setDown(boolean down) {
        this.down = down;
    }

    public int verifyCallCount(String rawApiKey) {
        return verifyCallsByKey.getOrDefault(rawApiKey, new AtomicInteger()).get();
    }

    public int resourceCallCount(UUID tenantId) {
        return resourceCallsByTenant.getOrDefault(tenantId, new AtomicInteger()).get();
    }

    private void handleVerify(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (down) {
                sendJson(exchange, 500, Map.of());
                return;
            }
            Map<?, ?> body = mapper.readValue(exchange.getRequestBody(), Map.class);
            String apiKey = String.valueOf(body.get("api_key"));
            verifyCallsByKey.computeIfAbsent(apiKey, k -> new AtomicInteger()).incrementAndGet();
            ApiKeyRecord record = apiKeysByRawKey.get(apiKey);
            if (record == null) {
                sendJson(exchange, 401, Map.of("code", "INVALID_API_KEY"));
                return;
            }
            sendJson(exchange, 200, Map.of(
                    "tenant_id", record.tenantId().toString(),
                    "api_key_id", record.apiKeyId().toString(),
                    "resource_ids", asStrings(record.resourceIds())));
        } finally {
            exchange.close();
        }
    }

    private void handleResources(HttpExchange exchange) throws IOException {
        try {
            String[] segments = exchange.getRequestURI().getPath().split("/");
            // /internal/v1/tenants/{id}/resources
            UUID tenantId = UUID.fromString(segments[4]);
            resourceCallsByTenant.computeIfAbsent(tenantId, id -> new AtomicInteger()).incrementAndGet();
            if (down) {
                sendJson(exchange, 500, Map.of());
                return;
            }
            Set<UUID> resourceIds = resourcesByTenant.getOrDefault(tenantId, Set.of());
            sendJson(exchange, 200, Map.of("tenant_id", tenantId.toString(), "resource_ids", asStrings(resourceIds)));
        } finally {
            exchange.close();
        }
    }

    private List<String> asStrings(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record ApiKeyRecord(UUID tenantId, UUID apiKeyId, Set<UUID> resourceIds) {
    }
}
