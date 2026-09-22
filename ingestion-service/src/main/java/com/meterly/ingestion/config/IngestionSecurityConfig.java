package com.meterly.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import com.meterly.common.security.ProblemDetailsSecurityHandlers;
import com.meterly.ingestion.security.ApiKeyAuthenticationFilter;
import com.meterly.ingestion.tenant.ApiKeyAuthCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Stateless security chain for ingestion-service. Both usage-event endpoints accept either an API key
 * (authenticated by {@link ApiKeyAuthenticationFilter}, authority {@code ROLE_API_KEY}) or a JWT with
 * {@code TENANT_ADMIN}; {@code hasAnyRole} expresses that directly, so Spring Security itself produces
 * 401 for no credentials and 403 for a {@code TENANT_USER} JWT (an authenticated principal simply
 * lacking the right role), without extra code in the controller.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, MeterlyJwtAuthenticationConverter converter,
            ProblemDetailsSecurityHandlers handlers, ApiKeyAuthCache authCache, ObjectMapper objectMapper)
            throws Exception {
        ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(authCache, objectMapper);
        http.csrf(csrf -> csrf.disable())
                .cors(withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/usage-events", "/v1/usage-events/batch")
                        .hasAnyRole("TENANT_ADMIN", "API_KEY")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers.entryPoint()))
                .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
