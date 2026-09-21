package com.meterly.ingestion;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.web.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class TenantClient {
    record Identity(UUID tenantId, UUID apiKeyId, List<UUID> resourceIds) {}
    private final RestClient client;
    TenantClient(RestClient.Builder builder, MeterlyProperties props,
                 @Value("${meterly.ingestion.tenant-service-url:http://localhost:8081}") String url) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = builder.baseUrl(url).requestFactory(factory).defaultHeader("X-Internal-Token", props.security().internalToken()).build();
    }
    Identity verify(String key) {
        if (key == null || !key.matches("mk_live_[A-Za-z0-9_-]{43}")) throw ApiException.unauthorized("INVALID_API_KEY", "Invalid API key");
        try { return client.post().uri("/internal/v1/api-keys/verify").body(Map.of("api_key", key)).retrieve().body(Identity.class); }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) throw ApiException.unauthorized("INVALID_API_KEY", "Invalid API key");
            throw unavailable();
        } catch (org.springframework.web.client.RestClientException e) { throw unavailable(); }
    }
    Identity resources(UUID tenant) {
        try { return client.get().uri("/internal/v1/tenants/{id}/resources", tenant).retrieve().body(Identity.class); }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) throw ApiException.forbidden("TENANT_INACTIVE", "Tenant is unavailable");
            throw unavailable();
        } catch (org.springframework.web.client.RestClientException e) { throw unavailable(); }
    }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Identity service is temporarily unavailable"); }
}
