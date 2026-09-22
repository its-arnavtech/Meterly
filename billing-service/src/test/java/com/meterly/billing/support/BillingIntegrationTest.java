package com.meterly.billing.support;

import com.meterly.common.security.Role;
import com.meterly.testsupport.MeterlyPostgres;
import com.meterly.testsupport.MeterlyRedpanda;
import com.meterly.testsupport.TestJwt;
import com.meterly.testsupport.TestJwtConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for billing-service integration tests: the real application against PostgreSQL 16 (as the
 * RLS-restricted {@code meterly_app} role) and Redpanda, trusting {@link TestJwt} tokens, with a fixed
 * clock. Every subclass shares one cached application context. Schedulers are disabled so tests drive the
 * period close and the outbox relay explicitly.
 */
@SpringBootTest(properties = {
        "meterly.billing.scheduling-enabled=false",
        "meterly.security.internal-token=" + BillingIntegrationTest.INTERNAL_TOKEN
})
@AutoConfigureMockMvc
@Import({TestJwtConfiguration.class, BillingIntegrationTest.FixedClock.class})
public abstract class BillingIntegrationTest {

    /** "Now" for every test: mid-September 2026, so August 2026 is the most recently closed period. */
    public static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    public static final String INTERNAL_TOKEN = "it-internal-token-0123456789abcdef";

    @Autowired
    protected MockMvc mvc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        MeterlyPostgres.registerDataSource(registry);
        MeterlyRedpanda.registerProperties(registry);
    }

    @BeforeEach
    void cleanDatabase() {
        MeterlyPostgres.truncateTenantData();
    }

    protected static String admin(UUID tenantId) {
        return TestJwt.bearer(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN);
    }

    protected static String user(UUID tenantId) {
        return TestJwt.bearer(tenantId, UUID.randomUUID(), Role.TENANT_USER);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
