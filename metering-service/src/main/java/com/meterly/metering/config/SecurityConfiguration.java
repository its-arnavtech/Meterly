package com.meterly.metering.config;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.security.InternalTokenFilter;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import com.meterly.common.security.ProblemDetailsSecurityHandlers;
import com.meterly.common.security.Role;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless HTTP security. Users authenticate with tenant-service JWTs (both roles may read usage);
 * {@code /internal/**} is for services presenting the shared internal token. The STOMP endpoint is
 * permitted at HTTP level because browsers cannot set headers on a WebSocket upgrade: the JWT travels in
 * the STOMP CONNECT frame and is validated by {@code StompAuthorizationInterceptor}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, MeterlyJwtAuthenticationConverter jwtConverter,
                                            ProblemDetailsSecurityHandlers handlers,
                                            MeterlyProperties properties) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/ws/**").permitAll()
                        .requestMatchers("/internal/**").hasRole(InternalTokenFilter.ROLE)
                        .requestMatchers(HttpMethod.GET, "/v1/usage/**")
                        .hasAnyRole(Role.TENANT_ADMIN.name(), Role.TENANT_USER.name())
                        .anyRequest().denyAll())
                .addFilterBefore(new InternalTokenFilter(properties.security().internalToken()),
                        BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(handlers.entryPoint()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()));
        return http.build();
    }
}
