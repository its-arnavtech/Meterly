package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Actuator, OpenAPI and the service's Micrometer counters are reachable without a token. */
class ObservabilityIT extends AbstractTenantServiceIT {

    private String prometheus() {
        ResponseEntity<String> metrics = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        return metrics.getBody();
    }

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

        String metrics = prometheus();

        assertThat(metrics).contains("meterly_tenant_signups_total");
        assertThat(metrics).contains("meterly_tenant_logins_total{application=\"tenant-service\",outcome=\"success\"}");
        assertThat(metrics).contains(
                "meterly_tenant_logins_total{application=\"tenant-service\",outcome=\"invalid_credentials\"}");
        assertThat(metrics).contains("meterly_tenant_token_refreshes_total");
    }

    @Test
    void unknownPathsAreDeniedRatherThanExposed() {
        TestRestTemplate anonymousClient = rest;

        assertThat(anonymousClient.getForEntity("/actuator/env", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(anonymousClient.getForEntity("/admin", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
