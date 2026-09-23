package com.meterly.tenant.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterly.tenant.config.TenantProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Availability of login beats throttling: when Redis cannot be reached the check is skipped and counted
 * instead of failing the request. (The happy path is covered end to end by {@code ThrottlingIT}.)
 */
class AuthThrottleTest {

    private static final TenantProperties PROPERTIES = new TenantProperties(
            new TenantProperties.Jwt(null, null, null, null, true, Duration.ofMinutes(15)),
            new TenantProperties.Throttle(null, null, null),
            4);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LettuceConnectionFactory connectionFactory = unreachableRedis();
    private final AuthThrottle throttle = new AuthThrottle(
            new RedisTokenBucket(new StringRedisTemplate(connectionFactory)), PROPERTIES, meterRegistry);

    private static LettuceConnectionFactory unreachableRedis() {
        // Port 1 is never a Redis: the connection is refused immediately.
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(250)).build());
        factory.afterPropertiesSet();
        return factory;
    }

    @AfterEach
    void closeConnections() {
        connectionFactory.destroy();
    }

    @Test
    void failsOpenAndCountsWhenRedisIsUnavailable() {
        ThrottleDecision login = throttle.login("203.0.113.7", "admin@acme.test");
        ThrottleDecision signup = throttle.signup("203.0.113.7");

        assertThat(login).isInstanceOf(ThrottleDecision.Unavailable.class);
        assertThat(signup).isInstanceOf(ThrottleDecision.Unavailable.class);
        assertThat(meterRegistry.get("meterly.tenant.throttle.fail.open").tag("scope", "login-ip").counter().count())
                .isEqualTo(1);
        assertThat(meterRegistry.get("meterly.tenant.throttle.fail.open").tag("scope", "login-email").counter()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.get("meterly.tenant.throttle.fail.open").tag("scope", "signup-ip").counter().count())
                .isEqualTo(1);
    }

    @Test
    void usesTheConfiguredDefaultLimits() {
        TenantProperties.Throttle limits = PROPERTIES.throttle();

        assertThat(limits.loginIp().capacity()).isEqualTo(50);
        assertThat(limits.loginEmail().capacity()).isEqualTo(10);
        assertThat(limits.signupIp().capacity()).isEqualTo(10);
        assertThat(limits.loginEmail().refillPerSecond()).isEqualTo(1 / 60.0);
    }
}
