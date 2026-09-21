package com.meterly.tenant;

import com.meterly.common.autoconfigure.*;
import com.meterly.common.security.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods=false)
@EnableMethodSecurity
public class TenantSecurity {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean JwtDecoder jwtDecoder(TokenIssuer issuer, MeterlyProperties properties) {
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withPublicKey(issuer.publicKey()).build();
        decoder.setJwtValidator(MeterlySecurityAutoConfiguration.meterlyTokenValidator(
                properties.security().jwt().issuer(),properties.security().jwt().audience()));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, MeterlyProperties properties,
            MeterlyJwtAuthenticationConverter converter, ProblemDetailsSecurityHandlers errors) throws Exception {
        return http.csrf(csrf->csrf.disable()).cors(withDefaults())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth
                    .requestMatchers("/actuator/health/**","/actuator/info","/actuator/prometheus","/v3/api-docs/**").permitAll()
                    .requestMatchers(HttpMethod.GET,"/.well-known/jwks.json","/v1/plans").permitAll()
                    .requestMatchers(HttpMethod.POST,"/v1/tenants","/v1/auth/login","/v1/auth/refresh","/v1/auth/logout").permitAll()
                    .requestMatchers("/internal/**").hasRole("INTERNAL")
                    .requestMatchers(HttpMethod.GET,"/v1/me","/v1/tenant","/v1/resources").hasAnyRole("TENANT_ADMIN","TENANT_USER")
                    .requestMatchers("/v1/**").hasRole("TENANT_ADMIN").anyRequest().denyAll())
                .exceptionHandling(e->e.authenticationEntryPoint(errors.entryPoint()).accessDeniedHandler(errors.accessDeniedHandler()))
                .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(errors.entryPoint()).accessDeniedHandler(errors.accessDeniedHandler()))
                .addFilterBefore(new InternalTokenFilter(properties.security().internalToken()),BearerTokenAuthenticationFilter.class)
                .build();
    }
}
