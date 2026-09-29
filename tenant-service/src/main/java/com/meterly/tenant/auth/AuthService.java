package com.meterly.tenant.auth;

import com.meterly.common.crypto.Hashing;
import com.meterly.common.web.ApiException;
import com.meterly.tenant.auth.AuthResults.RefreshResult;
import com.meterly.tenant.auth.AuthResults.RejectReason;
import com.meterly.tenant.auth.AuthResults.Rejected;
import com.meterly.tenant.auth.AuthResults.Rotated;
import com.meterly.tenant.auth.AuthResults.Session;
import com.meterly.tenant.config.AuthProperties;
import com.meterly.tenant.jwt.AccessTokenIssuer;
import com.meterly.tenant.security.PasswordPolicy;
import com.meterly.tenant.support.TenantTransactions;
import com.meterly.tenant.throttle.AuthThrottle;
import com.meterly.tenant.throttle.ThrottleDecision;
import com.meterly.tenant.throttle.TooManyRequestsException;
import com.meterly.tenant.user.Credentials;
import com.meterly.tenant.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login, refresh-token rotation and logout.
 *
 * <p>Login never reveals whether an email exists: unknown emails are checked against a dummy BCrypt hash so
 * the response time matches a wrong password, and every failure returns the same
 * {@code 401 INVALID_CREDENTIALS}. Refresh tokens rotate on every use; presenting a token that was already
 * rotated means it leaked (or was replayed), so the whole token family is revoked.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final Pattern REFRESH_TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthThrottle throttle;
    private final TenantTransactions transactions;
    private final AuthProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, AccessTokenIssuer accessTokens,
                       PasswordEncoder passwordEncoder, AuthThrottle throttle, TenantTransactions transactions,
                       AuthProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.transactions = transactions;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(Hashing.randomToken(24));
    }

    public Session login(String normalizedEmail, String password, String clientIp) {
        switch (throttle.login(clientIp, normalizedEmail)) {
            case ThrottleDecision.Limited limited -> {
                countLogin("throttled");
                throw TooManyRequestsException.attempts(limited.retryAfterSeconds());
            }
            case ThrottleDecision.Allowed allowed -> {
            }
            case ThrottleDecision.Unavailable unavailable -> {
            }
        }

        Optional<Credentials> credentials = users.findCredentials(normalizedEmail);
        String hash = credentials.map(Credentials::passwordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordMatches(password, hash);
        if (credentials.isEmpty() || !passwordMatches || !credentials.get().canSignIn()) {
            countLogin("invalid_credentials");
            throw invalidCredentials();
        }

        Credentials found = credentials.get();
        Session session = transactions.inTenant(found.tenantId(), () -> {
            // Re-read under RLS inside the transaction: the account may have changed since the lookup.
            UserRepository.Account account = users.findAccount(found.userId())
                    .filter(UserRepository.Account::active)
                    .orElseThrow(AuthService::invalidCredentials);
            return openSession(account, UUID.randomUUID());
        });
        countLogin("success");
        return session;
    }

    public RefreshResult refresh(String presentedToken) {
        Optional<RefreshTokenRepository.StoredToken> found = lookup(presentedToken);
        if (found.isEmpty()) {
            return rejected(RejectReason.UNKNOWN);
        }
        RefreshTokenRepository.StoredToken token = found.get();
        // A rejection is returned (not thrown) so the transaction commits any family revocation.
        RefreshResult result = transactions.inTenant(token.tenantId(), () -> rotate(token.id()));
        switch (result) {
            case Rotated rotated -> countRefresh("rotated");
            case Rejected rejected -> {
                countRefresh(rejected.reason().name().toLowerCase(Locale.ROOT));
                if (rejected.reason() == RejectReason.REUSED) {
                    log.warn("Refresh token reuse detected for user {} in tenant {}; token family {} revoked",
                            token.userId(), token.tenantId(), token.familyId());
                }
            }
        }
        return result;
    }

    /** Revokes the session (token family) of the presented refresh token, if it is known. */
    public void logout(String presentedToken) {
        lookup(presentedToken).ifPresent(token -> transactions.runInTenant(token.tenantId(),
                () -> refreshTokens.revokeFamily(token.familyId())));
    }

    private RefreshResult rotate(UUID tokenId) {
        Optional<RefreshTokenRepository.StoredToken> locked = refreshTokens.lock(tokenId);
        if (locked.isEmpty()) {
            return new Rejected(RejectReason.UNKNOWN);
        }
        RefreshTokenRepository.StoredToken current = locked.get();
        if (current.revokedAt() != null) {
            refreshTokens.revokeFamily(current.familyId());
            return new Rejected(current.wasRotated() ? RejectReason.REUSED : RejectReason.REVOKED);
        }
        if (!current.expiresAt().isAfter(clock.instant())) {
            refreshTokens.revokeFamily(current.familyId());
            return new Rejected(RejectReason.EXPIRED);
        }
        Optional<UserRepository.Account> account = users.findAccount(current.userId())
                .filter(UserRepository.Account::active);
        if (account.isEmpty()) {
            refreshTokens.revokeFamily(current.familyId());
            return new Rejected(RejectReason.ACCOUNT_INACTIVE);
        }
        Session session = openSession(account.get(), current.familyId());
        refreshTokens.markRotated(current.id(), session.refreshTokenId());
        return new Rotated(session);
    }

    /** Stores a new refresh token in {@code familyId} and issues the matching access token. */
    private Session openSession(UserRepository.Account account, UUID familyId) {
        String refreshToken = Hashing.randomToken(REFRESH_TOKEN_BYTES);
        UUID refreshTokenId = UUID.randomUUID();
        refreshTokens.insert(refreshTokenId, account.tenantId(), account.userId(), Hashing.sha256Hex(refreshToken),
                familyId, clock.instant().plus(properties.refreshTokenTtl()));
        AccessTokenIssuer.IssuedToken accessToken = accessTokens.issue(new AccessTokenIssuer.Subject(
                account.userId(), account.tenantId(), account.role(), account.email()));
        LoginResponse response = new LoginResponse(accessToken.value(), LoginResponse.BEARER,
                accessToken.expiresInSeconds(), new LoginResponse.AuthenticatedUser(account.userId(),
                        account.email(), account.role(), account.tenantId(), account.tenantName()));
        return new Session(response, refreshToken, refreshTokenId);
    }

    private Optional<RefreshTokenRepository.StoredToken> lookup(String presentedToken) {
        if (presentedToken == null || !REFRESH_TOKEN_FORMAT.matcher(presentedToken).matches()) {
            return Optional.empty();
        }
        return refreshTokens.findByHash(Hashing.sha256Hex(presentedToken));
    }

    /** Always runs one BCrypt comparison, so timing does not depend on whether the password is plausible. */
    private boolean passwordMatches(String password, String hash) {
        if (!PasswordPolicy.isAcceptable(password)) {
            passwordEncoder.matches("placeholder-password", dummyPasswordHash);
            return false;
        }
        return passwordEncoder.matches(password, hash);
    }

    private RefreshResult rejected(RejectReason reason) {
        countRefresh(reason.name().toLowerCase(Locale.ROOT));
        return new Rejected(reason);
    }

    private void countLogin(String outcome) {
        meterRegistry.counter("meterly.tenant.logins", "outcome", outcome).increment();
    }

    private void countRefresh(String outcome) {
        meterRegistry.counter("meterly.tenant.token.refreshes", "outcome", outcome).increment();
    }

    static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
    }
}
