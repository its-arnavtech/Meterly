package com.meterly.billing;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.meterly.common.security.InternalTokenFilter;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import com.meterly.common.security.ProblemDetailsSecurityHandlers;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class BillingConfiguration {
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http, MeterlyProperties properties,
                                 MeterlyJwtAuthenticationConverter converter, ProblemDetailsSecurityHandlers errors) throws Exception {
        return http.csrf(c -> c.disable()).cors(c -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/internal/**").hasRole("INTERNAL")
                        .requestMatchers("/v1/billing/**", "/v1/invoices/**").hasRole("TENANT_ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint(errors.entryPoint()))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors.entryPoint()).accessDeniedHandler(errors.accessDeniedHandler()))
                .addFilterBefore(new InternalTokenFilter(properties.security().internalToken()), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
