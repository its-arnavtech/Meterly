package com.meterly.ingestion;

import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import com.meterly.common.security.ProblemDetailsSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class IngestionSecurity {
    @Bean
    SecurityFilterChain security(HttpSecurity http, MeterlyJwtAuthenticationConverter converter,
                                 ProblemDetailsSecurityHandlers handlers) throws Exception {
        return http.csrf(c -> c.disable()).cors(c -> {}).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/v1/usage-events", "/v1/usage-events/batch").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers.entryPoint()).accessDeniedHandler(handlers.accessDeniedHandler()))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint(handlers.entryPoint()))
                .build();
    }
}
