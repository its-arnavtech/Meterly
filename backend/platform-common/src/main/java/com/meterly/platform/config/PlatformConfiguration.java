package com.meterly.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import com.meterly.platform.auth.JwtAuthenticationFilter;
import com.meterly.platform.auth.JwtService;
import com.meterly.platform.tenant.TenantTransactionAspect;
import com.meterly.platform.web.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {
    @Bean
    JwtService jwtService(
            ObjectMapper objectMapper,
            @Value("${meterly.auth.jwt-secret:}") String secret,
            @Value("${meterly.auth.token-seconds:3600}") long tokenSeconds,
            @Value("${meterly.auth.issuer-uri:}") String issuerUri,
            @Value("${meterly.auth.jwk-set-uri:}") String jwkSetUri
    ) {
        if (!issuerUri.isBlank()) {
            return JwtService.forIssuer(objectMapper, issuerUri, jwkSetUri);
        }
        return new JwtService(objectMapper, secret, tokenSeconds);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtService jwtService,
            ObjectProvider<MeterRegistry> meterRegistry
    ) {
        return new JwtAuthenticationFilter(jwtService, meterRegistry.getIfAvailable());
    }

    @Bean
    CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    TenantTransactionAspect tenantTransactionAspect(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectProvider<MeterRegistry> meterRegistry
    ) {
        return new TenantTransactionAspect(jdbcTemplate, transactionTemplate, meterRegistry.getIfAvailable());
    }
}
