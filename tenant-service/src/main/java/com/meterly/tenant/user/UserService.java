package com.meterly.tenant.user;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.security.Role;
import com.meterly.common.web.ApiException;
import com.meterly.tenant.account.TenantRepository;
import com.meterly.tenant.auth.RefreshTokenRepository;
import com.meterly.tenant.security.CallerVerifier;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User administration inside the caller's tenant (admin only). */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final TenantRepository tenants;
    private final RefreshTokenRepository refreshTokens;
    private final CallerVerifier callers;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, TenantRepository tenants, RefreshTokenRepository refreshTokens,
                       CallerVerifier callers, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.tenants = tenants;
        this.refreshTokens = refreshTokens;
        this.callers = callers;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<User> list(CurrentUser caller) {
        callers.requireActiveAdmin(caller);
        return users.list();
    }

    /** {@code passwordHash} is computed by the caller before the transaction (BCrypt is deliberately slow). */
    @Transactional
    public User create(CurrentUser caller, String email, String passwordHash, Role role) {
        callers.requireActiveAdmin(caller);
        if (users.emailTaken(email)) {
            throw emailTaken();
        }
        UUID id = UUID.randomUUID();
        try {
            users.insert(id, caller.tenantId(), email, role, passwordHash);
        } catch (DuplicateKeyException e) {
            throw emailTaken();
        }
        log.info("User {} created in tenant {} with role {}", id, caller.tenantId(), role);
        return users.find(id).orElseThrow();
    }

    public String hashPassword(String password) {
        return passwordEncoder.encode(password);
    }

    /**
     * Changes role and/or status. Invariants, checked under a tenant row lock so concurrent changes cannot
     * both pass: the tenant keeps at least one active admin ({@code LAST_ADMIN}, checked first), and admins
     * cannot disable or demote themselves ({@code CANNOT_MODIFY_SELF}). A user whose access shrinks loses
     * all refresh tokens, so the change takes effect no later than the current access token's expiry.
     */
    @Transactional
    public User update(CurrentUser caller, UUID id, Role newRole, User.UserStatus newStatus) {
        tenants.lock(caller.tenantId());
        callers.requireActiveAdmin(caller);
        User target = users.find(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User " + id + " not found"));
        Role role = newRole == null ? target.role() : newRole;
        User.UserStatus status = newStatus == null ? target.status() : newStatus;
        User updated = new User(target.id(), target.email(), role, status, target.createdAt());

        boolean losesAdmin = target.isActiveAdmin() && !updated.isActiveAdmin();
        if (losesAdmin && users.countActiveAdmins() <= 1) {
            throw ApiException.unprocessable("LAST_ADMIN", "The tenant must keep at least one active admin");
        }
        if (losesAdmin && id.equals(caller.userId())) {
            throw ApiException.unprocessable("CANNOT_MODIFY_SELF", "You cannot disable or demote yourself");
        }
        if (role == target.role() && status == target.status()) {
            return target;
        }
        users.update(id, role, status);
        if (status == User.UserStatus.DISABLED || role != target.role()) {
            int revoked = refreshTokens.revokeAllForUser(id);
            log.info("User {} in tenant {} changed to {}/{}; {} refresh token(s) revoked",
                    id, caller.tenantId(), role, status, revoked);
        }
        return users.find(id).orElseThrow();
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_TAKEN", "This email address is already registered");
    }
}
