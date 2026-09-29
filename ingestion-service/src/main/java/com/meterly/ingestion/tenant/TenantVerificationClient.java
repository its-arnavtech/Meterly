package com.meterly.ingestion.tenant;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.security.InternalTokenFilter;
import com.meterly.common.web.ApiException;
import com.meterly.ingestion.config.IngestionProperties;
import java.net.http.HttpClient;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Uncached calls to tenant-service's internal API. {@link com.meterly.ingestion.tenant.ApiKeyAuthCache}
 * and {@link com.meterly.ingestion.tenant.TenantResourceCache} own caching and refresh-once behaviour;
 * this class only talks HTTP.
 */
@Component
public class TenantVerificationClient {

    private static final Pattern API_KEY_FORMAT = Pattern.compile("mk_live_[A-Za-z0-9_-]{43}");

    private final RestClient restClient;

    public TenantVerificationClient(RestClient.Builder builder, MeterlyProperties meterlyProperties,
            IngestionProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.tenantServiceConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.tenantServiceReadTimeout());
        this.restClient = builder
                .baseUrl(properties.tenantServiceUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(InternalTokenFilter.HEADER, meterlyProperties.security().internalToken())
                .build();
    }

    /** Throws {@code 401 INVALID_API_KEY} for a malformed/unknown/revoked key, {@code 503 AUTH_UNAVAILABLE}
     * if tenant-service cannot be reached. */
    public ApiKeyVerification verify(String apiKey) {
        if (apiKey == null || !API_KEY_FORMAT.matcher(apiKey).matches()) {
            throw invalidKey();
        }
        try {
            ApiKeyVerification verification = restClient.post()
                    .uri("/internal/v1/api-keys/verify")
                    .body(new VerifyRequest(apiKey))
                    .retrieve()
                    .body(ApiKeyVerification.class);
            if (verification == null) {
                throw unavailable();
            }
            return verification;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                throw invalidKey();
            }
            throw unavailable();
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    /** Throws {@code 503 AUTH_UNAVAILABLE} if tenant-service cannot be reached; a JWT admin's own tenant
     * is assumed to exist, so any non-2xx response (including a stale/suspended tenant) is treated as
     * transient unavailability rather than a distinct client error. */
    public Set<UUID> resources(UUID tenantId) {
        try {
            ResourcesResponse response = restClient.get()
                    .uri("/internal/v1/tenants/{id}/resources", tenantId)
                    .retrieve()
                    .body(ResourcesResponse.class);
            return response == null ? Set.of() : Set.copyOf(response.resourceIds());
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private ApiException invalidKey() {
        return ApiException.unauthorized("INVALID_API_KEY", "Invalid API key");
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE",
                "Tenant service is temporarily unavailable");
    }

    private record VerifyRequest(String apiKey) {
    }

    private record ResourcesResponse(UUID tenantId, Set<UUID> resourceIds) {
    }
}
