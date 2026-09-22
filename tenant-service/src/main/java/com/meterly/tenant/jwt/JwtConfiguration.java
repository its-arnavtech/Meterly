package com.meterly.tenant.jwt;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.autoconfigure.MeterlySecurityAutoConfiguration;
import com.meterly.tenant.config.TenantProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * tenant-service is the token issuer, so it validates its own tokens locally (no JWKS round trip) with the
 * same validator every other service applies ({@code MeterlySecurityAutoConfiguration.meterlyTokenValidator}).
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {

    @Bean
    JwtKeyMaterial jwtKeyMaterial(TenantProperties properties, Environment environment) {
        TenantProperties.Jwt jwt = properties.jwt();
        boolean allowEphemeral = jwt.allowEphemeralKey() != null
                ? jwt.allowEphemeralKey()
                : !environment.acceptsProfiles(Profiles.of("prod"));
        return JwtKeyLoader.load(jwt, allowEphemeral);
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(JwtKeyMaterial keys, TenantProperties properties,
                                        MeterlyProperties meterly, Clock clock) {
        MeterlyProperties.Jwt jwt = meterly.security().jwt();
        return new AccessTokenIssuer(keys, jwt.issuer(), jwt.audience(), properties.jwt().accessTokenTtl(), clock);
    }

    /** Accepts tokens signed by the current key or any additional (rotated) public key, by {@code kid}. */
    @Bean
    JwtDecoder jwtDecoder(JwtKeyMaterial keys, MeterlyProperties meterly) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, new ImmutableJWKSet<>(keys.publicJwkSet())));
        // Claim checks are Spring's job (below); Nimbus' own verifier would duplicate them.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
        });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        MeterlyProperties.Jwt jwt = meterly.security().jwt();
        decoder.setJwtValidator(MeterlySecurityAutoConfiguration.meterlyTokenValidator(jwt.issuer(), jwt.audience()));
        return decoder;
    }
}
