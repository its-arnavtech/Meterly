package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Redis token buckets in front of the unauthenticated endpoints. Limits come from
 * {@code meterly.tenant.throttle.*}: 10 login attempts per email and 10 signups per IP by default.
 */
class ThrottlingIT extends AbstractTenantServiceIT {

    private static final int ATTEMPT_LIMIT = 40;

    @Test
    void repeatedFailedLoginsForOneEmailAreThrottled() {
        signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> throttled = null;
        int attempts = 0;
        while (attempts < ATTEMPT_LIMIT) {
            attempts++;
            ResponseEntity<JsonNode> response = loginRequest("admin@acme.test", "Wrong!Password" + attempts);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throttled = response;
                break;
            }
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(throttled).as("a 429 after at most %d attempts", ATTEMPT_LIMIT).isNotNull();
        assertThat(code(throttled)).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(Long.parseLong(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(throttled.getBody().get("retry_after_seconds").asLong()).isPositive();

        // The correct password is refused too while the bucket is empty: throttling precedes verification.
        assertThat(loginRequest("admin@acme.test", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void throttlingOneEmailDoesNotLockOutTheOthers() {
        signup("Acme Corp", "admin@acme.test");
        signup("Globex", "admin@globex.test");

        for (int attempt = 0; attempt < 12; attempt++) {
            loginRequest("admin@acme.test", "Wrong!Password" + attempt);
        }

        assertThat(loginRequest("admin@acme.test", PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(loginRequest("admin@globex.test", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void signupsAreThrottledPerClientAddress() {
        ResponseEntity<JsonNode> throttled = null;
        for (int attempt = 0; attempt < ATTEMPT_LIMIT && throttled == null; attempt++) {
            ResponseEntity<JsonNode> response =
                    signupRequest("Tenant " + attempt, "admin" + attempt + "@acme.test", PASSWORD, null);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throttled = response;
            } else {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
        }

        assertThat(throttled).isNotNull();
        assertThat(code(throttled)).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }
}
