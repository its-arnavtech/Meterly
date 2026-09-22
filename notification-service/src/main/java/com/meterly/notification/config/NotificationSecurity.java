package com.meterly.notification.config;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.security.InternalTokenFilter;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import com.meterly.common.security.ProblemDetailsSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security. Only TENANT_ADMIN users may read notification history; operational
 * endpoints (health probes, Prometheus scrape, OpenAPI document) are open on the service port, which is
 * never exposed through the SPA's reverse proxy.
 */
@Configuration
@EnableMethodSecurity
public class NotificationSecurity {

    @Bean
    SecurityFilterChain security(HttpSecurity http, MeterlyProperties properties,
                                 MeterlyJwtAuthenticationConverter converter,
                                 ProblemDetailsSecurityHandlers errors) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**").permitAll()
                        .requestMatchers("/internal/**").hasRole(InternalTokenFilter.ROLE)
                        .requestMatchers("/v1/notifications/**").hasRole("TENANT_ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(errors.entryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errors.entryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .addFilterBefore(new InternalTokenFilter(properties.security().internalToken()),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }
}
