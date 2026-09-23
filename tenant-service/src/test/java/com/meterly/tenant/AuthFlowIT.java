package com.meterly.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Signup, login, /v1/me, refresh-token rotation and reuse detection, logout, and the JWKS contract. */
class AuthFlowIT extends AbstractTenantServiceIT {

    @Test
    void signupCreatesTenantAdminSubscriptionAndDefaultResource() {
        ResponseEntity<JsonNode> response = signupRequest("Acme Corp", "admin@acme.test", PASSWORD, "GROWTH");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body.at("/tenant/name").asText()).isEqualTo("Acme Corp");
        assertThat(body.at("/tenant/status").asText()).isEqualTo("ACTIVE");
        assertThat(body.at("/admin/email").asText()).isEqualTo("admin@acme.test");
        assertThat(body.at("/admin/role").asText()).isEqualTo("TENANT_ADMIN");
        assertThat(body.at("/subscription/plan/code").asText()).isEqualTo("GROWTH");
        assertThat(body.at("/subscription/plan/base_price").asText()).isEqualTo("99.00");
        assertThat(body.at("/subscription/end_date").isNull()).isTrue();
        assertThat(body.at("/resources/0/name").asText()).isEqualTo("API calls");
        assertThat(body.at("/resources/0/unit_label").asText()).isEqualTo("call");
        assertThat(body.at("/admin").has("password_hash")).isFalse();
    }

    @Test
    void signupDefaultsToTheStarterPlanAndIsCaseInsensitiveAboutEmails() {
        ResponseEntity<JsonNode> created = signupRequest("Acme Corp", "Admin@Acme.test", PASSWORD, null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().at("/subscription/plan/code").asText()).isEqualTo("STARTER");
        assertThat(created.getBody().at("/admin/email").asText()).isEqualTo("admin@acme.test");

        ResponseEntity<JsonNode> duplicate = signupRequest("Acme Again", "ADMIN@acme.test", PASSWORD, null);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(duplicate)).isEqualTo("EMAIL_TAKEN");
        assertThat(duplicate.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(login("admin@acme.test", PASSWORD).email()).isEqualTo("admin@acme.test");
    }

    @Test
    void signupRejectsUnknownPlansAndInvalidInput() {
        ResponseEntity<JsonNode> unknownPlan = signupRequest("Acme Corp", "admin@acme.test", PASSWORD, "PLATINUM");
        assertThat(unknownPlan.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(unknownPlan)).isEqualTo("UNKNOWN_PLAN");

        ResponseEntity<JsonNode> shortPassword = signupRequest("Acme Corp", "admin@acme.test", "short", null);
        assertThat(shortPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(shortPassword)).isEqualTo("VALIDATION_FAILED");
        assertThat(shortPassword.getBody().get("errors")).isNotNull();
        assertThat(shortPassword.getBody().get("errors").get(0).get("message").asText())
                .contains("between 10 and 72 bytes");
        assertThat(shortPassword.getBody().hasNonNull("request_id")).isTrue();

        ResponseEntity<JsonNode> badEmail = signupRequest("Acme Corp", "not-an-email", PASSWORD, null);
        assertThat(badEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(badEmail)).isEqualTo("VALIDATION_FAILED");

        ResponseEntity<JsonNode> blankName = signupRequest(" ", "admin@acme.test", PASSWORD, null);
        assertThat(blankName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void everyLoginFailureLooksTheSame() {
        Session admin = signup("Acme Corp", "admin@acme.test");
        Session viewer = createUser(admin, "viewer@acme.test", "TENANT_USER");
        patch("/v1/users/" + viewer.userId(), Map.of("status", "DISABLED"), bearer(admin));

        ResponseEntity<JsonNode> unknownEmail = loginRequest("nobody@acme.test", PASSWORD);
        ResponseEntity<JsonNode> wrongPassword = loginRequest("admin@acme.test", "Wrong!Password9");
        ResponseEntity<JsonNode> disabledUser = loginRequest("viewer@acme.test", PASSWORD);

        for (ResponseEntity<JsonNode> response : java.util.List.of(unknownEmail, wrongPassword, disabledUser)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(code(response)).isEqualTo("INVALID_CREDENTIALS");
            assertThat(response.getBody().get("detail").asText()).isEqualTo("Invalid email or password");
        }
    }

    @Test
    void loginReturnsATokenAndAHardenedRefreshCookie() {
        signupRequest("Acme Corp", "admin@acme.test", PASSWORD, null);

        ResponseEntity<JsonNode> response = loginRequest("admin@acme.test", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("token_type").asText()).isEqualTo("Bearer");
        assertThat(response.getBody().get("expires_in").asLong()).isEqualTo(900);
        assertThat(response.getBody().at("/user/tenant_name").asText()).isEqualTo("Acme Corp");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(cookieHeader(response))
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/v1/auth")
                .contains("Max-Age=604800");
    }

    @Test
    void meReturnsTheUserAndTenantAndRequiresAuthentication() {
        Session admin = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> me = get("/v1/me", bearer(admin));
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().at("/user/id").asText()).isEqualTo(admin.userId().toString());
        assertThat(me.getBody().at("/user/status").asText()).isEqualTo("ACTIVE");
        assertThat(me.getBody().at("/tenant/id").asText()).isEqualTo(admin.tenantId().toString());

        ResponseEntity<JsonNode> anonymous = get("/v1/me", anonymous());
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(anonymous)).isEqualTo("UNAUTHENTICATED");

        ResponseEntity<JsonNode> garbage = get("/v1/me", bearer("not.a.token"));
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRotatesTheTokenAndReuseRevokesTheWholeFamily() {
        Session first = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> rotated = post("/v1/auth/refresh", null, refreshCookie(first.refreshToken()));
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondToken = refreshTokenOf(rotated);
        assertThat(secondToken).isNotNull().isNotEqualTo(first.refreshToken());
        assertThat(session(rotated).userId()).isEqualTo(first.userId());

        // The rotated token is dead...
        ResponseEntity<JsonNode> reuse = post("/v1/auth/refresh", null, refreshCookie(first.refreshToken()));
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(reuse)).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(cookieHeader(reuse)).contains("Max-Age=0");

        // ...and its reuse revoked the successor as well, so the session is over.
        ResponseEntity<JsonNode> afterReuse = post("/v1/auth/refresh", null, refreshCookie(secondToken));
        assertThat(afterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(afterReuse)).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void refreshIgnoresAStaleAuthorizationHeaderAndRejectsUnknownTokens() {
        Session session = signup("Acme Corp", "admin@acme.test");

        HttpHeaders staleToken = refreshCookie(session.refreshToken());
        staleToken.setBearerAuth("expired.access.token");
        assertThat(post("/v1/auth/refresh", null, staleToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(post("/v1/auth/refresh", null, anonymous()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(post("/v1/auth/refresh", null, refreshCookie("z".repeat(43)))))
                .isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() {
        Session session = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> logout = post("/v1/auth/logout", null, refreshCookie(session.refreshToken()));

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(cookieHeader(logout)).contains("Max-Age=0").contains("HttpOnly");
        assertThat(post("/v1/auth/refresh", null, refreshCookie(session.refreshToken())).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Logging out without a cookie is still a no-op success.
        assertThat(post("/v1/auth/logout", null, anonymous()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void theAccessTokenValidatesAgainstThePublishedJwksKey() throws Exception {
        Session session = signup("Acme Corp", "admin@acme.test");

        ResponseEntity<JsonNode> jwks = get("/.well-known/jwks.json", anonymous());

        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jwks.getHeaders().getCacheControl()).contains("max-age=300");
        JWKSet published = JWKSet.parse(jwks.getBody().toString());
        SignedJWT token = SignedJWT.parse(session.accessToken());
        RSAKey key = (RSAKey) published.getKeyByKeyId(token.getHeader().getKeyID());
        assertThat(key).isNotNull();
        assertThat(key.isPrivate()).isFalse();
        assertThat(token.verify(new RSASSAVerifier(key))).isTrue();
        assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo("meterly");
        assertThat(token.getJWTClaimsSet().getAudience()).containsExactly("meterly-api");
        assertThat(token.getJWTClaimsSet().getStringClaim("tenant_id")).isEqualTo(session.tenantId().toString());
        assertThat(token.getJWTClaimsSet().getStringClaim("role")).isEqualTo("TENANT_ADMIN");
    }

    @Test
    void plansArePublicAndPricedAsStrings() {
        ResponseEntity<JsonNode> plans = get("/v1/plans", anonymous());

        assertThat(plans.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode starter = plans.getBody().get("items").get(0);
        assertThat(starter.get("code").asText()).isEqualTo("STARTER");
        assertThat(starter.get("base_price").isTextual()).isTrue();
        assertThat(starter.get("base_price").asText()).isEqualTo("19.00");
        assertThat(starter.get("included_units").asLong()).isEqualTo(10_000);
        assertThat(starter.at("/tiers/0/unit_price").asText()).isEqualTo("0.002000");
        assertThat(starter.at("/tiers/0/from_unit").asLong()).isEqualTo(10_000);
        assertThat(starter.at("/tiers/1/to_unit").isNull()).isTrue();
    }
}
