package com.meterly.tenant.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterly.tenant.config.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RefreshCookieTest {

    private static RefreshCookie cookie(boolean secure) {
        return new RefreshCookie(new AuthProperties(secure, Duration.ofDays(7)));
    }

    @Test
    void isHttpOnlyStrictAndScopedToTheAuthEndpoints() {
        String header = cookie(false).issue("token-value");

        assertThat(header).startsWith("meterly_refresh=token-value")
                .contains("Max-Age=604800")
                .contains("Path=/v1/auth")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Secure");
    }

    @Test
    void addsSecureWhenConfigured() {
        assertThat(cookie(true).issue("token-value")).contains("; Secure");
    }

    @Test
    void clearsWithTheSameAttributesAndAnImmediateExpiry() {
        String header = cookie(true).clear();

        assertThat(header).startsWith("meterly_refresh=")
                .contains("Max-Age=0")
                .contains("Path=/v1/auth")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Secure")
                .doesNotContain("token");
    }
}
