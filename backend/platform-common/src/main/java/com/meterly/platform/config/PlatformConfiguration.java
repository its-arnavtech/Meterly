package com.meterly.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.platform.auth.JwtAuthenticationFilter;
import com.meterly.platform.auth.JwtService;
import com.meterly.platform.tenant.TenantTransactionAspect;
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
            @Value("${meterly.auth.jwt-secret}") String secret,
            @Value("${meterly.auth.token-seconds:3600}") long tokenSeconds
    ) {
        return new JwtService(objectMapper, secret, tokenSeconds);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    TenantTransactionAspect tenantTransactionAspect(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        return new TenantTransactionAspect(jdbcTemplate, transactionTemplate);
    }
}
