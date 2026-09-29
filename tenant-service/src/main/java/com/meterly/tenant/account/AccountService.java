package com.meterly.tenant.account;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.web.ApiException;
import com.meterly.tenant.security.CallerVerifier;
import com.meterly.tenant.user.User;
import com.meterly.tenant.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The caller's own profile and tenant. */
@Service
public class AccountService {

    private final TenantRepository tenants;
    private final UserRepository users;
    private final CallerVerifier callers;

    public AccountService(TenantRepository tenants, UserRepository users, CallerVerifier callers) {
        this.tenants = tenants;
        this.users = users;
        this.callers = callers;
    }

    /** Response of {@code GET /v1/me}. */
    public record Me(User user, Tenant tenant) {
    }

    @Transactional(readOnly = true)
    public Me me(CurrentUser caller) {
        callers.requireActive(caller);
        User user = users.find(caller.userId()).orElseThrow(AccountService::sessionInvalid);
        return new Me(user, tenant(caller));
    }

    @Transactional(readOnly = true)
    public Tenant get(CurrentUser caller) {
        callers.requireActive(caller);
        return tenant(caller);
    }

    @Transactional
    public Tenant rename(CurrentUser caller, String name) {
        callers.requireActiveAdmin(caller);
        tenants.rename(caller.tenantId(), name);
        return tenant(caller);
    }

    private Tenant tenant(CurrentUser caller) {
        return tenants.find(caller.tenantId()).orElseThrow(AccountService::sessionInvalid);
    }

    private static ApiException sessionInvalid() {
        return ApiException.unauthorized("UNAUTHENTICATED", "Your session is no longer valid");
    }
}
