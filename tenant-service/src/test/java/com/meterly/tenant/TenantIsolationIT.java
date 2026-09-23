package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Cross-tenant isolation through the public API, and the role boundary between TENANT_ADMIN and
 * TENANT_USER. Rows of another tenant are invisible (RLS), so they read as "not found", never as data.
 */
class TenantIsolationIT extends AbstractTenantServiceIT {

    private record Fixture(Session admin, UUID resourceId, UUID userId, UUID apiKeyId) {
    }

    private Fixture tenant(String name, String email) {
        Session admin = signup(name, email);
        UUID resourceId = UUID.fromString(post("/v1/resources",
                Map.of("name", name + " webhooks", "unit_label", "delivery"), bearer(admin))
                .getBody().get("id").asText());
        UUID userId = UUID.fromString(post("/v1/users",
                Map.of("email", "viewer." + email, "password", PASSWORD, "role", "TENANT_USER"), bearer(admin))
                .getBody().get("id").asText());
        UUID apiKeyId = UUID.fromString(post("/v1/api-keys", Map.of("name", name + " key"), bearer(admin))
                .getBody().get("id").asText());
        return new Fixture(admin, resourceId, userId, apiKeyId);
    }

    @Test
    void oneTenantNeverSeesAnotherTenantsData() {
        Fixture acme = tenant("Acme Corp", "admin@acme.test");
        Fixture globex = tenant("Globex", "admin@globex.test");

        assertThat(get("/v1/tenant", bearer(globex.admin())).getBody().get("name").asText()).isEqualTo("Globex");

        JsonNode resources = get("/v1/resources", bearer(globex.admin())).getBody().get("items");
        assertThat(resources).hasSize(2);
        assertThat(resources.findValuesAsText("id")).doesNotContain(acme.resourceId().toString());

        JsonNode users = get("/v1/users", bearer(globex.admin())).getBody().get("items");
        assertThat(users.findValuesAsText("email"))
                .containsExactlyInAnyOrder("admin@globex.test", "viewer.admin@globex.test");

        JsonNode apiKeys = get("/v1/api-keys", bearer(globex.admin())).getBody().get("items");
        assertThat(apiKeys).hasSize(1);
        assertThat(apiKeys.get(0).get("id").asText()).isEqualTo(globex.apiKeyId().toString());
    }

    @Test
    void writesToAnotherTenantsRowsAreNotFound() {
        Fixture acme = tenant("Acme Corp", "admin@acme.test");
        Session globex = signup("Globex", "admin@globex.test");

        ResponseEntity<JsonNode> resource = patch("/v1/resources/" + acme.resourceId(),
                Map.of("name", "stolen"), bearer(globex));
        assertThat(resource.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(resource)).isEqualTo("RESOURCE_NOT_FOUND");

        ResponseEntity<JsonNode> user = patch("/v1/users/" + acme.userId(),
                Map.of("status", "DISABLED"), bearer(globex));
        assertThat(user.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(user)).isEqualTo("USER_NOT_FOUND");

        ResponseEntity<JsonNode> apiKey = delete("/v1/api-keys/" + acme.apiKeyId(), bearer(globex));
        assertThat(apiKey.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(apiKey)).isEqualTo("API_KEY_NOT_FOUND");

        // Nothing changed for the owning tenant.
        assertThat(get("/v1/resources", bearer(acme.admin())).getBody().get("items").findValuesAsText("name"))
                .contains("Acme Corp webhooks");
        assertThat(get("/v1/api-keys", bearer(acme.admin())).getBody().at("/items/0/revoked_at").isNull()).isTrue();
    }

    @Test
    void tenantUsersOnlyReachTheDashboardEndpoints() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        Session viewer = createUser(admin, "viewer@acme.test", "TENANT_USER");

        assertThat(get("/v1/me", bearer(viewer)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/tenant", bearer(viewer)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/resources", bearer(viewer)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post("/v1/resources", Map.of("name", "Nope", "unit_label", "call"), bearer(viewer))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patch("/v1/tenant", Map.of("name", "Renamed"), bearer(viewer))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/users", bearer(viewer)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/api-keys", bearer(viewer)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/subscription", bearer(viewer)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> forbidden = post("/v1/api-keys", Map.of("name", "Nope"), bearer(viewer));
        assertThat(code(forbidden)).isEqualTo("FORBIDDEN");
    }

    @Test
    void adminsCanRenameTheirOwnTenantOnly() {
        Session acme = signup("Acme Corp", "admin@acme.test");
        Session globex = signup("Globex", "admin@globex.test");

        assertThat(patch("/v1/tenant", Map.of("name", "Acme Incorporated"), bearer(acme)).getBody()
                .get("name").asText()).isEqualTo("Acme Incorporated");
        assertThat(get("/v1/tenant", bearer(globex)).getBody().get("name").asText()).isEqualTo("Globex");

        ResponseEntity<JsonNode> tooShort = patch("/v1/tenant", Map.of("name", " x "), bearer(acme));
        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(tooShort)).isEqualTo("VALIDATION_FAILED");
    }
}
