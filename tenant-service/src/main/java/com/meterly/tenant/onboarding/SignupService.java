package com.meterly.tenant.onboarding;

import com.meterly.common.security.Role;
import com.meterly.common.web.ApiException;
import com.meterly.tenant.account.Tenant;
import com.meterly.tenant.account.TenantRepository;
import com.meterly.tenant.plan.Plan;
import com.meterly.tenant.plan.PlanRepository;
import com.meterly.tenant.resource.Resource;
import com.meterly.tenant.resource.ResourceRepository;
import com.meterly.tenant.subscription.Subscription;
import com.meterly.tenant.subscription.SubscriptionService;
import com.meterly.tenant.support.TenantTransactions;
import com.meterly.tenant.throttle.AuthThrottle;
import com.meterly.tenant.throttle.ThrottleDecision;
import com.meterly.tenant.throttle.TooManyRequestsException;
import com.meterly.tenant.user.User;
import com.meterly.tenant.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Tenant onboarding. The tenant id is generated here, so the whole signup runs in one transaction bound
 * to that id: RLS then admits the new tenant row and all of its children, and a failure leaves nothing.
 */
@Service
public class SignupService {

    public static final String DEFAULT_PLAN = "STARTER";

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ResourceRepository resources;
    private final PlanRepository plans;
    private final SubscriptionService subscriptions;
    private final TenantTransactions transactions;
    private final PasswordEncoder passwordEncoder;
    private final AuthThrottle throttle;
    private final Counter signups;

    public SignupService(TenantRepository tenants, UserRepository users, ResourceRepository resources,
                         PlanRepository plans, SubscriptionService subscriptions, TenantTransactions transactions,
                         PasswordEncoder passwordEncoder, AuthThrottle throttle, MeterRegistry meterRegistry) {
        this.tenants = tenants;
        this.users = users;
        this.resources = resources;
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.transactions = transactions;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.signups = Counter.builder("meterly.tenant.signups")
                .description("Tenants created through POST /v1/tenants")
                .register(meterRegistry);
    }

    public record Signup(String tenantName, String adminEmail, String adminPassword, String planCode) {

        @Override
        public String toString() {
            return "Signup[tenantName=" + tenantName + ", adminEmail=" + adminEmail + ", adminPassword=<redacted>]";
        }
    }

    public record SignupResult(Tenant tenant, User admin, Subscription subscription, List<Resource> resources) {
    }

    public SignupResult signup(Signup signup, String clientIp) {
        if (throttle.signup(clientIp) instanceof ThrottleDecision.Limited limited) {
            throw TooManyRequestsException.requests(limited.retryAfterSeconds());
        }
        String planCode = signup.planCode() == null ? DEFAULT_PLAN : signup.planCode();
        Plan plan = plans.findActiveByCode(planCode)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_PLAN", "Unknown or inactive plan: " + planCode));
        if (users.emailTaken(signup.adminEmail())) {
            throw emailTaken();
        }
        String passwordHash = passwordEncoder.encode(signup.adminPassword());

        UUID tenantId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SignupResult result;
        try {
            result = transactions.inTenant(tenantId, () -> {
                tenants.insert(tenantId, signup.tenantName());
                users.insert(adminId, tenantId, signup.adminEmail(), Role.TENANT_ADMIN, passwordHash);
                subscriptions.start(tenantId, plan.id());
                resources.insert(UUID.randomUUID(), tenantId,
                        ResourceRepository.DEFAULT_NAME, ResourceRepository.DEFAULT_UNIT_LABEL);
                return new SignupResult(
                        tenants.find(tenantId).orElseThrow(),
                        users.find(adminId).orElseThrow(),
                        subscriptions.current(),
                        resources.list());
            });
        } catch (DuplicateKeyException e) {
            // Lost a race with a concurrent signup for the same email (unique index on lower(email)).
            throw emailTaken();
        }
        signups.increment();
        log.info("Tenant {} signed up on plan {}", tenantId, plan.code());
        return result;
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_TAKEN", "This email address is already registered");
    }
}
