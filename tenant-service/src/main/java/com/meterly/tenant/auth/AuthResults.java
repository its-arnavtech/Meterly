package com.meterly.tenant.auth;

import java.util.UUID;

/** Results of the session operations in {@link AuthService}. */
public final class AuthResults {

    private AuthResults() {
    }

    /** A new session state: the response body plus the raw refresh token destined for the cookie. */
    public record Session(LoginResponse response, String refreshToken, UUID refreshTokenId) {

        @Override
        public String toString() {
            return "Session[user=" + response.user().id() + ", refreshToken=<redacted>]";
        }
    }

    /** Outcome of exchanging a refresh token. */
    public sealed interface RefreshResult {
    }

    public record Rotated(Session session) implements RefreshResult {
    }

    public record Rejected(RejectReason reason) implements RefreshResult {
    }

    public enum RejectReason {
        /** Missing, malformed or unknown token. */
        UNKNOWN,
        /** Revoked by logout or an account change. */
        REVOKED,
        /** An already-rotated token was presented again: the whole family has been revoked. */
        REUSED,
        EXPIRED,
        /** The user was disabled or the tenant suspended. */
        ACCOUNT_INACTIVE
    }
}
