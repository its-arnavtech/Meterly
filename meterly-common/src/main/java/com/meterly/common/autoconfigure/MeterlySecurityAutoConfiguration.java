package com.meterly.common.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterly.common.security.CurrentUserArgumentResolver;
import com.meterly.common.security.MeterlyJwtAuthenticationConverter;
import com.meterly.common.security.ProblemDetailsSecurityHandlers;
import com.meterly.common.tenant.TenantContextAspect;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Security building blocks shared by all services: JWT decoding against tenant-service's JWKS,
 * role mapping, problem+json 401/403 handlers, the {@link TenantContextAspect} and the
 * {@code CurrentUser} argument resolver. Each service still declares its own {@code SecurityFilterChain}.
 */
@AutoConfiguration(
        after = MeterlyCoreAutoConfiguration.class,
        beforeName = "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration")
@ConditionalOnClass({JwtAuthenticationToken.class, JwtDecoder.class})
public class MeterlySecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MeterlyJwtAuthenticationConverter meterlyJwtAuthenticationConverter() {
        return new MeterlyJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantContextAspect tenantContextAspect() {
        return new TenantContextAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailsSecurityHandlers problemDetailsSecurityHandlers(ObjectMapper objectMapper) {
        return new ProblemDetailsSecurityHandlers(objectMapper);
    }

    /** Validates RS256 tokens against the issuer's JWKS; enforces exp/nbf, {@code iss} and {@code aud}. */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    @ConditionalOnProperty(prefix = "meterly.security.jwt", name = "jwks-uri")
    public JwtDecoder meterlyJwtDecoder(MeterlyProperties properties) {
        MeterlyProperties.Jwt jwt = properties.security().jwt();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.jwksUri().toString()).build();
        decoder.setJwtValidator(meterlyTokenValidator(jwt.issuer(), jwt.audience()));
        return decoder;
    }

    /** The validator every Meterly JWT must pass; tenant-service reuses it for its own decoder. */
    public static OAuth2TokenValidator<Jwt> meterlyTokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", aud -> aud != null && aud.contains(audience));
        OAuth2TokenValidator<Jwt> tenantClaimPresent = new JwtClaimValidator<String>(
                "tenant_id", value -> value != null && !value.isBlank());
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, tenantClaimPresent);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class CurrentUserResolverConfiguration implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new CurrentUserArgumentResolver());
        }
    }
}
