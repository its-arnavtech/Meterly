package com.meterly.tenant.apikey;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.web.ApiException;
import com.meterly.tenant.resource.ResourceRepository;
import com.meterly.tenant.security.CallerVerifier;
import com.meterly.tenant.support.TenantTransactions;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API-key issuance and revocation for admins, and verification for ingestion-service. */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository apiKeys;
    private final ResourceRepository resources;
    private final CallerVerifier callers;
    private final TenantTransactions transactions;

    public ApiKeyService(ApiKeyRepository apiKeys, ResourceRepository resources, CallerVerifier callers,
                         TenantTransactions transactions) {
        this.apiKeys = apiKeys;
        this.resources = resources;
        this.callers = callers;
        this.transactions = transactions;
    }

    /** Result of a successful verification: the key's tenant and its active resources. */
    public record Verification(UUID tenantId, UUID apiKeyId, List<UUID> resourceIds) {
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(CurrentUser caller) {
        callers.requireActiveAdmin(caller);
        return apiKeys.list();
    }

    @Transactional
    public ApiKey.Created create(CurrentUser caller, String name) {
        callers.requireActiveAdmin(caller);
        ApiKeys.GeneratedKey key = ApiKeys.generate();
        UUID id = UUID.randomUUID();
        apiKeys.insert(id, caller.tenantId(), name, key.displayPrefix(), key.hash(), caller.userId());
        log.info("API key {} ({}) created in tenant {} by user {}", id, key.displayPrefix(), caller.tenantId(),
                caller.userId());
        return new ApiKey.Created(apiKeys.find(id).orElseThrow(), key.rawKey());
    }

    @Transactional
    public void revoke(CurrentUser caller, UUID id) {
        callers.requireActiveAdmin(caller);
        if (!apiKeys.revoke(id)) {
            throw ApiException.notFound("API_KEY_NOT_FOUND", "API key " + id + " not found");
        }
        log.info("API key {} revoked in tenant {} by user {}", id, caller.tenantId(), caller.userId());
    }

    /**
     * Resolves a raw key to its tenant. Unknown and revoked keys and keys of suspended tenants all yield the
     * same {@code 401 INVALID_API_KEY}.
     */
    public Verification verify(String rawKey) {
        if (!ApiKeys.isPlausible(rawKey)) {
            throw invalidKey();
        }
        ApiKeyRepository.KeyOwner owner = apiKeys.findByHash(ApiKeys.hash(rawKey))
                .filter(found -> !found.revoked() && found.tenantActive())
                .orElseThrow(ApiKeyService::invalidKey);
        return transactions.inTenant(owner.tenantId(), () -> {
            // Re-check inside the tenant transaction: the key may have been revoked since the lookup.
            if (!apiKeys.isUsable(owner.apiKeyId())) {
                throw invalidKey();
            }
            apiKeys.touchLastUsed(owner.apiKeyId());
            return new Verification(owner.tenantId(), owner.apiKeyId(), resources.activeIds());
        });
    }

    private static ApiException invalidKey() {
        return ApiException.unauthorized("INVALID_API_KEY", "The API key is invalid or has been revoked");
    }
}
