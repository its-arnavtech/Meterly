package com.meterly.billing.config;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.security.InternalTokenFilter;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import com.meterly.common.security.ProblemDetailsSecurityHandlers;
import com.meterly.common.security.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource server: JWTs from tenant-service for {@code /v1/**} (TENANT_ADMIN only — billing is
 * not part of the TENANT_USER dashboard), the shared internal token for {@code /internal/**}, and
 * problem+json 401/403 responses. Anything not listed is denied.
 */
@Configuration(proxyBeanMethods = false)
public class BillingSecurityConfiguration {

    @Bean
    SecurityFilterChain billingSecurityFilterChain(HttpSecurity http, MeterlyProperties properties,
                                                   MeterlyJwtAuthenticationConverter jwtConverter,
                                                   ProblemDetailsSecurityHandlers errors) throws Exception {
        String admin = Role.TENANT_ADMIN.name();
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**").permitAll()
                        .requestMatchers("/internal/**").hasRole(InternalTokenFilter.ROLE)
                        .requestMatchers("/v1/billing/**", "/v1/invoices", "/v1/invoices/**").hasRole(admin)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(errors.entryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errors.entryPoint())
                        .accessDeniedHandler(errors.accessDeniedHandler()))
                .addFilterBefore(new InternalTokenFilter(properties.security().internalToken()),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }
}
