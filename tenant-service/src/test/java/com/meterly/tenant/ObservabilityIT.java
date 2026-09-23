package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Actuator and OpenAPI are reachable without a token, and the service's Micrometer counters appear in the
 * Prometheus scrape. {@code @AutoConfigureObservability} is needed because Boot disables metrics export
 * (and therefore the scrape endpoint) in tests by default.
 */
@AutoConfigureObservability
class ObservabilityIT extends AbstractTenantServiceIT {

    @Test
    void healthInfoAndOpenApiArePublic() {
        assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> apiDocs = get("/v3/api-docs", anonymous());
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiDocs.getBody().get("paths").has("/v1/tenants")).isTrue();
        assertThat(apiDocs.getBody().get("paths").has("/v1/api-keys")).isTrue();
    }

    @Test
    void signupAndLoginOutcomesAreCounted() {
        signup("Acme Corp", "admin@acme.test");
        loginRequest("admin@acme.test", "Wrong!Password1");

        ResponseEntity<String> scrape = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        String metrics = scrape.getBody();
        assertThat(metrics).contains("meterly_tenant_signups_total");
        assertThat(metrics).contains("meterly_tenant_logins_total");
        assertThat(metrics).contains("outcome=\"success\"");
        assertThat(metrics).contains("outcome=\"invalid_credentials\"");
        assertThat(metrics).contains("application=\"tenant-service\"");
    }

    @Test
    void unknownAndUnexposedPathsAreNotServed() {
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/admin", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
