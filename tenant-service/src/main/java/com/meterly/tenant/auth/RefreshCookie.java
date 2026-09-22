package com.meterly.tenant.auth;

import com.meterly.tenant.config.AuthProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh-token cookie: {@code HttpOnly} (unreadable by scripts), {@code SameSite=Strict} (never sent
 * on cross-site requests, which is what makes CSRF protection unnecessary), {@code Path=/v1/auth} (only
 * sent to the auth endpoints) and {@code Secure} when {@code meterly.auth.refresh-cookie-secure=true}.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "meterly_refresh";
    public static final String PATH = "/v1/auth";

    private final AuthProperties properties;

    public RefreshCookie(AuthProperties properties) {
        this.properties = properties;
    }

    public String issue(String refreshToken) {
        return build(refreshToken, properties.refreshTokenTtl());
    }

    public String clear() {
        return build("", Duration.ZERO);
    }

    private String build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.refreshCookieSecure())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
