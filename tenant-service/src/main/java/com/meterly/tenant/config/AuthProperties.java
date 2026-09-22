package com.meterly.tenant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refresh-token session settings under {@code meterly.auth.*} (the prefix is fixed by the API contract).
 *
 * <pre>
 * meterly.auth.refresh-cookie-secure   add the Secure attribute to the refresh cookie (env REFRESH_COOKIE_SECURE)
 * meterly.auth.refresh-token-ttl       lifetime of a refresh token and its cookie (default 7d)
 * </pre>
 */
@ConfigurationProperties("meterly.auth")
public record AuthProperties(
        @DefaultValue("false") boolean refreshCookieSecure,
        @DefaultValue("7d") Duration refreshTokenTtl) {

    public AuthProperties {
        if (refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            throw new IllegalArgumentException("meterly.auth.refresh-token-ttl must be positive");
        }
    }
}
