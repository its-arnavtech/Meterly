package com.meterly.tenant.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.security.MeterlyClaims;
import com.meterly.common.security.Role;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.meterly.tenant.config.TenantProperties;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/** The issued token must carry exactly the contract claims and validate like any other Meterly token. */
class AccessTokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-09-21T17:04:05Z");
    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtKeyMaterial keys = JwtKeyLoader.load(
            new TenantProperties.Jwt(null, null, "test-kid", List.of(), true, Duration.ofMinutes(15)), true);
    private final AccessTokenIssuer issuer =
            new AccessTokenIssuer(keys, "meterly", "meterly-api", Duration.ofMinutes(15), clock);
    /** Decoders enforce {@code exp} against the real clock, so round-trip tests must issue "now". */
    private final AccessTokenIssuer liveIssuer =
            new AccessTokenIssuer(keys, "meterly", "meterly-api", Duration.ofMinutes(15), Clock.systemUTC());

    private static AccessTokenIssuer.Subject subject() {
        return new AccessTokenIssuer.Subject(USER, TENANT, Role.TENANT_ADMIN, "admin@acme.test");
    }

    private static JwtDecoder decoder(JwtKeyMaterial keys) {
        MeterlyProperties properties = new MeterlyProperties(
                new MeterlyProperties.Security(new MeterlyProperties.Jwt(null, "meterly", "meterly-api"), "x"),
                new MeterlyProperties.Cors(List.of("http://localhost:4200")));
        return new JwtConfiguration().jwtDecoder(keys, properties);
    }

    @Test
    void carriesExactlyTheContractClaims() throws ParseException {
        JWSObject token = JWSObject.parse(issuer.issue(subject()).value());

        assertThat(token.getHeader().getKeyID()).isEqualTo("test-kid");
        assertThat(token.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        Map<String, Object> claims = token.getPayload().toJSONObject();
        assertThat(claims).containsOnlyKeys("iss", "aud", "sub", "iat", "exp",
                MeterlyClaims.TENANT_ID, MeterlyClaims.ROLE, MeterlyClaims.EMAIL);
        assertThat(claims.get("iss")).isEqualTo("meterly");
        assertThat(claims.get("aud")).isEqualTo(List.of("meterly-api"));
        assertThat(claims.get("sub")).isEqualTo(USER.toString());
        assertThat(claims.get("iat")).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.get("exp")).isEqualTo(NOW.plus(Duration.ofMinutes(15)).getEpochSecond());
        assertThat(claims.get(MeterlyClaims.TENANT_ID)).isEqualTo(TENANT.toString());
        assertThat(claims.get(MeterlyClaims.ROLE)).isEqualTo("TENANT_ADMIN");
        assertThat(claims.get(MeterlyClaims.EMAIL)).isEqualTo("admin@acme.test");
    }

    @Test
    void expiresAfterFifteenMinutes() {
        AccessTokenIssuer.IssuedToken issued = issuer.issue(subject());

        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void validatesAgainstTheServicesOwnDecoder() {
        Jwt decoded = decoder(keys).decode(liveIssuer.issue(subject()).value());

        assertThat(decoded.getSubject()).isEqualTo(USER.toString());
        assertThat(decoded.getAudience()).containsExactly("meterly-api");
        assertThat(decoded.getClaimAsString(MeterlyClaims.TENANT_ID)).isEqualTo(TENANT.toString());
    }

    @Test
    void validatesAgainstThePublishedJwksKey() throws ParseException {
        JWKSet published = JWKSet.parse(keys.jwksDocument());
        RSAKey publicKey = (RSAKey) published.getKeyByKeyId("test-kid");

        assertThat(publicKey.isPrivate()).isFalse();
        assertThat(publicKey.getKeyUse().identifier()).isEqualTo("sig");
        assertThat(publicKey.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(JwksVerification.verifies(issuer.issue(subject()).value(), publicKey)).isTrue();
    }

    @Test
    void rejectsAnExpiredTokenAndOneSignedByAnotherKey() {
        AccessTokenIssuer expired = new AccessTokenIssuer(keys, "meterly", "meterly-api", Duration.ofMinutes(15),
                Clock.fixed(NOW.minus(Duration.ofHours(2)), ZoneOffset.UTC));
        JwtKeyMaterial otherKeys = JwtKeyLoader.load(
                new TenantProperties.Jwt(null, null, "other", List.of(), true, Duration.ofMinutes(15)), true);
        AccessTokenIssuer foreign = new AccessTokenIssuer(otherKeys, "meterly", "meterly-api",
                Duration.ofMinutes(15), clock);

        JwtDecoder decoder = decoder(keys);
        assertThatThrownBy(() -> decoder.decode(expired.issue(subject()).value()))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(foreign.issue(subject()).value()))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    void acceptsTokensSignedByARetiredKeyThatIsStillPublished() throws Exception {
        JwtKeyMaterial retiredKeys = JwtKeyLoader.load(
                new TenantProperties.Jwt(null, null, "retired", List.of(), true, Duration.ofMinutes(15)), true);
        JwtKeyMaterial rotated = new JwtKeyMaterial(keys.signingKey(), List.of(retiredKeys.signingKey()), false);
        AccessTokenIssuer retiredIssuer = new AccessTokenIssuer(retiredKeys, "meterly", "meterly-api",
                Duration.ofMinutes(15), Clock.systemUTC());

        Jwt decoded = decoder(rotated).decode(retiredIssuer.issue(subject()).value());

        assertThat(decoded.getHeaders().get("kid")).isEqualTo("retired");
        assertThat(JWKSet.parse(rotated.jwksDocument()).getKeys()).hasSize(2);
    }
}
