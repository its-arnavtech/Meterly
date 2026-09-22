package com.meterly.tenant.throttle;

import com.meterly.common.redis.RedisKeys;
import com.meterly.tenant.config.TenantProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Throttling policy for the unauthenticated endpoints: login is limited per client IP and per email
 * (credential stuffing and targeted guessing), signup per client IP. Redis keys hash the subject
 * ({@link RedisKeys#authThrottle}), so no email address or IP is stored in clear.
 *
 * <p>If Redis is unavailable the request is allowed (availability of login beats throttling) and
 * {@code meterly.tenant.throttle.fail.open} is incremented so the outage is visible and alertable.
 */
@Component
public class AuthThrottle {

    static final String LOGIN_IP = "login-ip";
    static final String LOGIN_EMAIL = "login-email";
    static final String SIGNUP_IP = "signup-ip";

    private final RedisTokenBucket buckets;
    private final TenantProperties.Throttle limits;
    private final MeterRegistry meterRegistry;

    public AuthThrottle(RedisTokenBucket buckets, TenantProperties properties, MeterRegistry meterRegistry) {
        this.buckets = buckets;
        this.limits = properties.throttle();
        this.meterRegistry = meterRegistry;
    }

    /** Takes a token from the client's IP bucket and, if that passed, from the email's bucket. */
    public ThrottleDecision login(String clientIp, String normalizedEmail) {
        ThrottleDecision byIp = take(LOGIN_IP, clientIp, limits.loginIp());
        if (byIp instanceof ThrottleDecision.Limited) {
            return byIp;
        }
        ThrottleDecision byEmail = take(LOGIN_EMAIL, normalizedEmail, limits.loginEmail());
        if (byEmail instanceof ThrottleDecision.Limited) {
            return byEmail;
        }
        return byIp instanceof ThrottleDecision.Unavailable ? byIp : byEmail;
    }

    public ThrottleDecision signup(String clientIp) {
        return take(SIGNUP_IP, clientIp, limits.signupIp());
    }

    private ThrottleDecision take(String scope, String subject, TenantProperties.Bucket bucket) {
        ThrottleDecision decision = buckets.take(RedisKeys.authThrottle(scope, subject), bucket);
        if (decision instanceof ThrottleDecision.Unavailable) {
            failOpenCounter(scope).increment();
        }
        return decision;
    }

    private Counter failOpenCounter(String scope) {
        return Counter.builder("meterly.tenant.throttle.fail.open")
                .description("Throttle checks skipped because Redis was unavailable")
                .tag("scope", scope)
                .register(meterRegistry);
    }
}
