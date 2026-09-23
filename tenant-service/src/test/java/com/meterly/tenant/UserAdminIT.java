package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** User administration: creation, role/status changes, and the last-admin and self-modification guards. */
class UserAdminIT extends AbstractTenantServiceIT {

    @Test
    void adminsCreateUsersAndDuplicateEmailsAreRejected() {
        Session admin = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> created = post("/v1/users",
                Map.of("email", "Viewer@Acme.test", "password", PASSWORD, "role", "TENANT_USER"), bearer(admin));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("email").asText()).isEqualTo("viewer@acme.test");
        assertThat(created.getBody().get("role").asText()).isEqualTo("TENANT_USER");
        assertThat(created.getBody().has("password_hash")).isFalse();

        ResponseEntity<JsonNode> duplicate = post("/v1/users",
                Map.of("email", "viewer@acme.test", "password", PASSWORD, "role", "TENANT_USER"), bearer(admin));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(duplicate)).isEqualTo("EMAIL_TAKEN");

        ResponseEntity<JsonNode> badRole = post("/v1/users",
                Map.of("email", "other@acme.test", "password", PASSWORD, "role", "ROOT"), bearer(admin));
        assertThat(badRole.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(badRole)).isEqualTo("VALIDATION_FAILED");

        assertThat(get("/v1/users", bearer(admin)).getBody().get("items")).hasSize(2);
    }

    @Test
    void disablingAUserEndsTheirAccessImmediately() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        Session viewer = createUser(admin, "viewer@acme.test", "TENANT_USER");
        assertThat(get("/v1/me", bearer(viewer)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> disabled = patch("/v1/users/" + viewer.userId(),
                Map.of("status", "DISABLED"), bearer(admin));
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody().get("status").asText()).isEqualTo("DISABLED");

        // The access token is still cryptographically valid, but the account is checked on every request.
        ResponseEntity<JsonNode> afterDisable = get("/v1/me", bearer(viewer));
        assertThat(afterDisable.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(afterDisable)).isEqualTo("UNAUTHENTICATED");
        // ...and the session cannot be refreshed either.
        assertThat(post("/v1/auth/refresh", null, refreshCookie(viewer.refreshToken())).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void demotingAnAdminRevokesTheirAdminPowersAndSessions() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        Session second = createUser(admin, "ops@acme.test", "TENANT_ADMIN");
        assertThat(get("/v1/users", bearer(second)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> demoted = patch("/v1/users/" + second.userId(),
                Map.of("role", "TENANT_USER"), bearer(admin));
        assertThat(demoted.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> forbidden = get("/v1/users", bearer(second));
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(forbidden)).isEqualTo("FORBIDDEN");
        assertThat(get("/v1/me", bearer(second)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/v1/auth/refresh", null, refreshCookie(second.refreshToken())).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login("ops@acme.test", PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void theLastActiveAdminCannotBeRemoved() {
        Session admin = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> demoteSelf = patch("/v1/users/" + admin.userId(),
                Map.of("role", "TENANT_USER"), bearer(admin));
        assertThat(demoteSelf.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(demoteSelf)).isEqualTo("LAST_ADMIN");

        ResponseEntity<JsonNode> disableSelf = patch("/v1/users/" + admin.userId(),
                Map.of("status", "DISABLED"), bearer(admin));
        assertThat(disableSelf.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(disableSelf)).isEqualTo("LAST_ADMIN");

        assertThat(get("/v1/users", bearer(admin)).getBody().at("/items/0/role").asText()).isEqualTo("TENANT_ADMIN");
    }

    @Test
    void anAdminCannotDemoteOrDisableThemselvesWhileOthersRemain() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        createUser(admin, "ops@acme.test", "TENANT_ADMIN");

        ResponseEntity<JsonNode> self = patch("/v1/users/" + admin.userId(),
                Map.of("role", "TENANT_USER"), bearer(admin));

        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(self)).isEqualTo("CANNOT_MODIFY_SELF");
        assertThat(get("/v1/users", bearer(admin)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownUsersAreNotFoundAndNoOpPatchesSucceed() {
        Session admin = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> unknown = patch("/v1/users/" + UUID.randomUUID(),
                Map.of("role", "TENANT_USER"), bearer(admin));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(unknown)).isEqualTo("USER_NOT_FOUND");

        ResponseEntity<JsonNode> unchanged = patch("/v1/users/" + admin.userId(), Map.of(), bearer(admin));
        assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unchanged.getBody().get("role").asText()).isEqualTo("TENANT_ADMIN");
    }
}
