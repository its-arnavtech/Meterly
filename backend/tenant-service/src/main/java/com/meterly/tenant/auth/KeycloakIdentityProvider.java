package com.meterly.tenant.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.meterly.platform.auth.AuthPrincipal;
import com.meterly.platform.auth.JwtService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "meterly.auth.provider", havingValue = "keycloak")
final class KeycloakIdentityProvider implements IdentityProvider {
    private final RestClient restClient;
    private final JwtService jwtService;
    private final String baseUrl;
    private final String realm;
    private final String loginClientId;
    private final String adminClientId;
    private final String adminClientSecret;

    KeycloakIdentityProvider(
            RestClient.Builder restClientBuilder,
            JwtService jwtService,
            @Value("${meterly.auth.keycloak.base-url}") String baseUrl,
            @Value("${meterly.auth.keycloak.realm:meterly}") String realm,
            @Value("${meterly.auth.keycloak.login-client-id:meterly-web}") String loginClientId,
            @Value("${meterly.auth.keycloak.admin-client-id:meterly-admin}") String adminClientId,
            @Value("${meterly.auth.keycloak.admin-client-secret}") String adminClientSecret
    ) {
        this.restClient = restClientBuilder.build();
        this.jwtService = jwtService;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.realm = realm;
        this.loginClientId = loginClientId;
        this.adminClientId = adminClientId;
        this.adminClientSecret = adminClientSecret;
    }

    @Override
    public IdentitySession login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", loginClientId);
        form.add("username", email);
        form.add("password", password);
        try {
            JsonNode response = token(form);
            String accessToken = response.path("access_token").asText();
            AuthPrincipal principal = jwtService.verify(accessToken)
                    .orElseThrow(() -> new BadCredentialsException(
                            "Keycloak token is invalid or missing Meterly tenant claims"));
            if (accessToken.isBlank()) {
                throw new BadCredentialsException("Keycloak token is missing Meterly tenant claims");
            }
            return new IdentitySession(
                    accessToken, principal.tenantId(), principal.email(), principal.role());
        } catch (HttpClientErrorException exception) {
            throw new BadCredentialsException("Invalid email or password", exception);
        } catch (BadCredentialsException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not obtain or parse Keycloak token", exception);
        }
    }

    @Override
    public String createUser(String email, String password, UUID tenantId, String role) {
        Map<String, Object> representation = Map.of(
                "username", email,
                "email", email,
                "firstName", "Meterly",
                "lastName", "User",
                "enabled", true,
                "emailVerified", true,
                "attributes", Map.of(
                        "tenant_id", List.of(tenantId.toString()),
                        "role", List.of(role)
                ),
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", false
                ))
        );
        URI location = restClient.post()
                .uri(baseUrl + "/admin/realms/" + realm + "/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(representation)
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation();
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return the created user location");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public void deleteUser(String externalUserId) {
        if (externalUserId == null || externalUserId.isBlank()) {
            return;
        }
        restClient.delete()
                .uri(baseUrl + "/admin/realms/" + realm + "/users/" + externalUserId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve()
                .toBodilessEntity();
    }

    private String adminToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", adminClientId);
        form.add("client_secret", adminClientSecret);
        return token(form).path("access_token").asText();
    }

    private JsonNode token(MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(baseUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
    }

}
