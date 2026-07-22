package com.meterly.tenant.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/api-keys")
public class InternalApiKeyController {
    private final ApiKeyService apiKeyService;
    private final byte[] internalServiceKey;

    public InternalApiKeyController(
            ApiKeyService apiKeyService,
            @Value("${meterly.internal.service-key}") String internalServiceKey
    ) {
        this.apiKeyService = apiKeyService;
        this.internalServiceKey = internalServiceKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/introspect")
    public IntrospectionResponse introspect(
            @RequestHeader("X-Internal-Service-Key") String suppliedInternalKey,
            @Valid @RequestBody IntrospectionRequest request
    ) {
        if (!MessageDigest.isEqual(
                internalServiceKey,
                suppliedInternalKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return apiKeyService.introspect(request.apiKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public record IntrospectionRequest(@NotBlank String apiKey) {
    }

    public record IntrospectionResponse(UUID tenantId, List<UUID> resourceIds) {
    }
}
