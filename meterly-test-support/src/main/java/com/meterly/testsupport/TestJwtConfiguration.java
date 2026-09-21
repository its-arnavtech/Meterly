package com.meterly.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Makes a service trust {@link TestJwt} tokens instead of fetching tenant-service's JWKS. */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfiguration {

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return TestJwt.decoder();
    }
}
