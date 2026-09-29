package com.meterly.ingestion.ingest;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.web.ApiException;
import com.meterly.ingestion.ratelimit.RateLimitDecision;
import com.meterly.ingestion.ratelimit.TokenBucketRateLimiter;
import com.meterly.ingestion.security.ApiKeyAuthentication;
import com.meterly.ingestion.tenant.ApiKeyAuthCache;
import com.meterly.ingestion.tenant.TenantResourceCache;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/usage-events} and {@code POST /v1/usage-events/batch} (contract §2). Authentication
 * (API key or {@code TENANT_ADMIN} JWT) is enforced by the security chain; this controller resolves the
 * tenant and its resource set from whichever {@link Authentication} the chain produced, applies the
 * per-tenant rate limit, and delegates per-event validation/idempotency/publish to
 * {@link IngestionService}. The tenant always comes from the credential, never the request body.
 */
@RestController
@RequestMapping("/v1/usage-events")
public class IngestionController {

    private final IngestionService ingestionService;
    private final TokenBucketRateLimiter rateLimiter;
    private final ApiKeyAuthCache apiKeyAuthCache;
    private final TenantResourceCache tenantResourceCache;
    private final Validator validator;

    public IngestionController(IngestionService ingestionService, TokenBucketRateLimiter rateLimiter,
            ApiKeyAuthCache apiKeyAuthCache, TenantResourceCache tenantResourceCache, Validator validator) {
        this.ingestionService = ingestionService;
        this.rateLimiter = rateLimiter;
        this.apiKeyAuthCache = apiKeyAuthCache;
        this.tenantResourceCache = tenantResourceCache;
        this.validator = validator;
    }

    @PostMapping
    public ResponseEntity<Object> ingestOne(Authentication authentication,
            @Valid @RequestBody IngestionRequests.UsageEvent event, HttpServletResponse response) {
        UUID tenantId = tenantIdOf(authentication);
        applyRateLimit(tenantId, 1, response);
        ResourceIdSource resources = resourceIdSource(authentication, tenantId);
        IngestionOutcome outcome = ingestionService.ingest(tenantId, resources, event);
        return IngestionOutcomeMapper.toResponseEntity(outcome);
    }

    @PostMapping("/batch")
    public IngestionResponses.BatchResult ingestBatch(Authentication authentication,
            @Valid @RequestBody IngestionRequests.Batch batch, HttpServletResponse response) {
        UUID tenantId = tenantIdOf(authentication);
        applyRateLimit(tenantId, batch.events().size(), response);
        ResourceIdSource resources = resourceIdSource(authentication, tenantId);
        List<IngestionResponses.BatchItem> results = new ArrayList<>(batch.events().size());
        for (IngestionRequests.UsageEvent event : batch.events()) {
            results.add(processBatchItem(tenantId, resources, event));
        }
        return new IngestionResponses.BatchResult(results);
    }

    /** Batch items are validated manually (not via {@code @Valid} cascade) so one malformed event
     * becomes a {@code rejected} result instead of failing the entire batch with a 400. */
    private IngestionResponses.BatchItem processBatchItem(UUID tenantId, ResourceIdSource resources,
            IngestionRequests.UsageEvent event) {
        Set<ConstraintViolation<IngestionRequests.UsageEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .collect(Collectors.joining("; "));
            String idempotencyKey = event.idempotencyKey() == null ? "" : event.idempotencyKey();
            return IngestionResponses.BatchItem.rejected(idempotencyKey, "VALIDATION_FAILED", detail);
        }
        IngestionOutcome outcome = ingestionService.ingest(tenantId, resources, event);
        return IngestionOutcomeMapper.toBatchItem(outcome);
    }

    private void applyRateLimit(UUID tenantId, int cost, HttpServletResponse response) {
        RateLimitDecision decision = rateLimiter.consume(tenantId, cost);
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Tenant usage-event rate limit exceeded");
        }
    }

    private UUID tenantIdOf(Authentication authentication) {
        return switch (authentication) {
            case ApiKeyAuthentication apiKeyAuth -> apiKeyAuth.tenantId();
            case JwtAuthenticationToken jwtAuth -> CurrentUser.from(jwtAuth.getToken()).tenantId();
            case null, default ->
                    throw ApiException.unauthorized("UNAUTHENTICATED", "An API key or bearer token is required");
        };
    }

    private ResourceIdSource resourceIdSource(Authentication authentication, UUID tenantId) {
        return switch (authentication) {
            case ApiKeyAuthentication apiKeyAuth -> new ApiKeyResourceIdSource(apiKeyAuthCache, apiKeyAuth);
            case JwtAuthenticationToken jwtAuth -> new JwtResourceIdSource(tenantResourceCache, tenantId);
            case null, default ->
                    throw ApiException.unauthorized("UNAUTHENTICATED", "An API key or bearer token is required");
        };
    }
}
