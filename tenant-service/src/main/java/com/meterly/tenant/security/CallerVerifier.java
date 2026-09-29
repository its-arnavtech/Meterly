package com.meterly.tenant.security;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.security.Role;
import com.meterly.common.web.ApiException;
import com.meterly.tenant.user.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Re-checks the caller against the database inside the request's tenant transaction.
 *
 * <p>An access token stays valid for up to 15 minutes, but tenant-service owns the user records, so it can
 * do better than the token: a user who was disabled or demoted, or whose tenant was suspended, loses access
 * to tenant-service immediately. {@code @PreAuthorize} still performs the fast token-level role check first.
 */
@Component
public class CallerVerifier {

    private final UserRepository users;

    public CallerVerifier(UserRepository users) {
        this.users = users;
    }

    /** The caller must still exist and be active in an active tenant; otherwise 401. */
    public UserRepository.Account requireActive(CurrentUser caller) {
        return users.findAccount(caller.userId())
                .filter(UserRepository.Account::active)
                .filter(account -> account.tenantId().equals(caller.tenantId()))
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Your session is no longer valid"));
    }

    /** As {@link #requireActive}, and the caller must currently be a TENANT_ADMIN; otherwise 403. */
    public UserRepository.Account requireActiveAdmin(CurrentUser caller) {
        UserRepository.Account account = requireActive(caller);
        if (account.role() != Role.TENANT_ADMIN) {
            throw ApiException.forbidden("FORBIDDEN", "You do not have permission to perform this action");
        }
        return account;
    }
}
