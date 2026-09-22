package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.meterly.common.security.InternalTokenFilter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** API-key issuance, verification by ingestion-service and the other {@code /internal/**} endpoints. */
class ApiKeyIT extends AbstractTenantServiceIT {

    private ResponseEntity<JsonNode> verify(String rawKey, HttpHeaders headers) {
        return post("/internal/v1/api-keys/verify", Map.of("api_key", rawKey), headers);
    }

    @Test
    void createdKeyIsShownOnceAndVerifiesUntilItIsRevoked() {
        Session admin = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> created = post("/v1/api-keys", Map.of("name", "Production"), bearer(admin));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getCacheControl()).contains("no-store");
        String rawKey = created.getBody().get("key").asText();
        UUID keyId = UUID.fromString(created.getBody().get("id").asText());
        assertThat(rawKey).startsWith("mk_live_").hasSize(51);
        assertThat(created.getBody().get("prefix").asText()).isEqualTo(rawKey.substring(0, 12));
        assertThat(created.getBody().get("revoked_at").isNull()).isTrue();

        JsonNode listed = get("/v1/api-keys", bearer(admin)).getBody().get("items").get(0);
        assertThat(listed.has("key")).as("the raw key is returned only once").isFalse();
        assertThat(listed.get("name").asText()).isEqualTo("Production");

        ResponseEntity<JsonNode> verified = verify(rawKey, internal());
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody().get("tenant_id").asText()).isEqualTo(admin.tenantId().toString());
        assertThat(verified.getBody().get("api_key_id").asText()).isEqualTo(keyId.toString());
        assertThat(verified.getBody().get("resource_ids")).hasSize(1);

        assertThat(delete("/v1/api-keys/" + keyId, bearer(admin)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/v1/api-keys", bearer(admin)).getBody().at("/items/0/revoked_at").isNull()).isFalse();

        ResponseEntity<JsonNode> afterRevoke = verify(rawKey, internal());
        assertThat(afterRevoke.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(afterRevoke)).isEqualTo("INVALID_API_KEY");
    }

    @Test
    void verifyReportsOnlyActiveResourcesAndRecordsUsageAtMostOncePerMinute() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        UUID archived = UUID.fromString(post("/v1/resources", Map.of("name", "Webhooks", "unit_label", "delivery"),
                bearer(admin)).getBody().get("id").asText());
        patch("/v1/resources/" + archived, Map.of("status", "ARCHIVED"), bearer(admin));
        String rawKey = post("/v1/api-keys", Map.of("name", "Production"), bearer(admin)).getBody()
                .get("key").asText();

        JsonNode resourceIds = verify(rawKey, internal()).getBody().get("resource_ids");
        assertThat(resourceIds).hasSize(1);
        assertThat(resourceIds.get(0).asText()).isNotEqualTo(archived.toString());

        String firstUse = get("/v1/api-keys", bearer(admin)).getBody().at("/items/0/last_used_at").asText();
        assertThat(firstUse).isNotBlank();
        verify(rawKey, internal());
        assertThat(get("/v1/api-keys", bearer(admin)).getBody().at("/items/0/last_used_at").asText())
                .isEqualTo(firstUse);
    }

    @Test
    void unknownAndMalformedKeysAreRejectedIdentically() {
        signup("Acme Corp", "admin@acme.test");

        for (String candidate : java.util.List.of("mk_live_" + "a".repeat(43), "garbage", "mk_live_ ")) {
            ResponseEntity<JsonNode> response = verify(candidate, internal());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(code(response)).isEqualTo("INVALID_API_KEY");
        }

        ResponseEntity<JsonNode> blank = verify("", internal());
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(blank)).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void internalEndpointsRequireTheSharedToken() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        String path = "/internal/v1/tenants/" + admin.tenantId() + "/resources";

        assertThat(get(path, anonymous()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders wrongToken = new HttpHeaders();
        wrongToken.set(InternalTokenFilter.HEADER, "not-the-shared-secret");
        ResponseEntity<JsonNode> rejected = get(path, wrongToken);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(rejected)).isEqualTo("UNAUTHENTICATED");

        // A user token is not an internal credential, however privileged the user is.
        assertThat(get(path, bearer(admin)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void directoryEndpointsServeResourcesAndAdminRecipients() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        createUser(admin, "viewer@acme.test", "TENANT_USER");
        Session second = createUser(admin, "ops@acme.test", "TENANT_ADMIN");

        ResponseEntity<JsonNode> resources = get("/internal/v1/tenants/" + admin.tenantId() + "/resources",
                internal());
        assertThat(resources.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resources.getBody().get("tenant_id").asText()).isEqualTo(admin.tenantId().toString());
        assertThat(resources.getBody().get("resource_ids")).hasSize(1);

        ResponseEntity<JsonNode> admins = get("/internal/v1/tenants/" + admin.tenantId() + "/admins", internal());
        assertThat(admins.getBody().get("items").findValuesAsText("email"))
                .containsExactly("admin@acme.test", "ops@acme.test");
        assertThat(second.userId()).isNotNull();

        ResponseEntity<JsonNode> unknown = get("/internal/v1/tenants/" + UUID.randomUUID() + "/admins", internal());
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(unknown)).isEqualTo("TENANT_NOT_FOUND");
    }

    @Test
    void aKeyOfOneTenantNeverAuthorizesAnother() {
        Session acme = signup("Acme Corp", "admin@acme.test");
        Session globex = signup("Globex", "admin@globex.test");
        String acmeKey = post("/v1/api-keys", Map.of("name", "Acme"), bearer(acme)).getBody().get("key").asText();

        ResponseEntity<JsonNode> verified = verify(acmeKey, internal());

        assertThat(verified.getBody().get("tenant_id").asText())
                .isEqualTo(acme.tenantId().toString())
                .isNotEqualTo(globex.tenantId().toString());
    }
}
