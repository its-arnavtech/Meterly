package com.meterly.tenant.security;

import static org.springframework.security.config.Customizer.withDefaults;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.security.InternalTokenFilter;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import com.meterly.common.security.ProblemDetailsSecurityHandlers;
import com.meterly.tenant.config.TenantProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Stateless bearer-token security for tenant-service.
 *
 * <ul>
 *   <li>Public: signup, login/refresh/logout, plan catalog, JWKS, health/info/prometheus, OpenAPI.</li>
 *   <li>{@code /internal/**}: {@link InternalTokenFilter} + {@code ROLE_INTERNAL}.</li>
 *   <li>Everything else under {@code /v1/**}: a valid Meterly JWT; admin-only operations are guarded with
 *       {@code @PreAuthorize} on the controllers and re-checked against the database by {@link CallerVerifier}.</li>
 * </ul>
 * CSRF protection is unnecessary: the API authenticates with bearer tokens, and the only cookie
 * ({@code meterly_refresh}) is {@code SameSite=Strict} and scoped to {@code /v1/auth}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final PathPatternRequestMatcher.Builder PUBLIC_PATHS = PathPatternRequestMatcher.withDefaults();

    /** Endpoints that never need a user token; a stale Authorization header must not break them. */
    static final RequestMatcher PUBLIC_ENDPOINTS = new OrRequestMatcher(
            PUBLIC_PATHS.matcher(HttpMethod.POST, "/v1/tenants"),
            PUBLIC_PATHS.matcher(HttpMethod.POST, "/v1/auth/login"),
            PUBLIC_PATHS.matcher(HttpMethod.POST, "/v1/auth/refresh"),
            PUBLIC_PATHS.matcher(HttpMethod.POST, "/v1/auth/logout"),
            PUBLIC_PATHS.matcher(HttpMethod.GET, "/v1/plans"),
            PUBLIC_PATHS.matcher(HttpMethod.GET, "/.well-known/jwks.json"));

    @Bean
    PasswordEncoder passwordEncoder(TenantProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, MeterlyProperties properties,
                                            MeterlyJwtAuthenticationConverter jwtConverter,
                                            ProblemDetailsSecurityHandlers problemHandlers) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/error").permitAll()
                        .requestMatchers("/internal/**").hasRole(InternalTokenFilter.ROLE)
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandlers.entryPoint())
                        .accessDeniedHandler(problemHandlers.accessDeniedHandler()))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(problemHandlers.entryPoint())
                        .accessDeniedHandler(problemHandlers.accessDeniedHandler()))
                .addFilterBefore(new InternalTokenFilter(properties.security().internalToken()),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }

    /**
     * Ignores the Authorization header on public endpoints: a browser that still holds an expired access
     * token must be able to call {@code /v1/auth/refresh} (or log in again) without being rejected with 401.
     */
    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return request -> PUBLIC_ENDPOINTS.matches(request) ? null : standard.resolve(request);
    }
}
