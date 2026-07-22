package com.meterly.ingestion.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class TenantServiceClient {
    private final RestClient restClient;
    private final String internalServiceKey;

    public TenantServiceClient(
            RestClient.Builder builder,
            @Value("${meterly.tenant-service.base-url}") String tenantServiceBaseUrl,
            @Value("${meterly.internal.service-key}") String internalServiceKey
    ) {
        this.restClient = builder.baseUrl(tenantServiceBaseUrl).build();
        this.internalServiceKey = internalServiceKey;
    }

    public Optional<ApiKeyPrincipal> introspect(String apiKey) {
        try {
            IntrospectionResponse response = restClient.post()
                    .uri("/internal/v1/api-keys/introspect")
                    .header("X-Internal-Service-Key", internalServiceKey)
                    .body(new IntrospectionRequest(apiKey))
                    .retrieve()
                    .body(IntrospectionResponse.class);
            if (response == null) {
                return Optional.empty();
            }
            return Optional.of(new ApiKeyPrincipal(response.tenantId(), Set.copyOf(response.resourceIds())));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    private record IntrospectionRequest(String apiKey) {
    }

    private record IntrospectionResponse(UUID tenantId, List<UUID> resourceIds) {
    }
}
