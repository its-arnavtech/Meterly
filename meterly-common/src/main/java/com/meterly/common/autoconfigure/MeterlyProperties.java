package com.meterly.common.autoconfigure;

import com.meterly.common.security.MeterlyClaims;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cross-service settings under the {@code meterly.*} prefix. Service-specific settings live in each
 * service's own properties record (e.g. {@code meterly.billing.*}).
 *
 * <pre>
 * meterly.security.jwt.jwks-uri      JWKS endpoint of tenant-service (validators only)
 * meterly.security.jwt.issuer        expected iss claim (default "meterly")
 * meterly.security.jwt.audience      expected aud claim (default "meterly-api")
 * meterly.security.internal-token    shared secret for /internal/** service-to-service calls
 * meterly.cors.allowed-origins       SPA origin(s); nothing else may call the API from a browser
 * </pre>
 */
@ConfigurationProperties("meterly")
public record MeterlyProperties(@DefaultValue Security security, @DefaultValue Cors cors) {

    public record Security(@DefaultValue Jwt jwt, String internalToken) {
    }

    public record Jwt(
            URI jwksUri,
            @DefaultValue(MeterlyClaims.DEFAULT_ISSUER) String issuer,
            @DefaultValue(MeterlyClaims.DEFAULT_AUDIENCE) String audience) {
    }

    public record Cors(@DefaultValue("http://localhost:4200") List<String> allowedOrigins) {
    }
}
