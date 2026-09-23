package com.meterly.tenant.auth;

import com.meterly.common.web.ApiException;
import com.meterly.tenant.auth.AuthResults.Rejected;
import com.meterly.tenant.auth.AuthResults.Rotated;
import com.meterly.tenant.auth.AuthResults.Session;
import com.meterly.tenant.support.Text;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final RefreshCookie refreshCookie;

    public AuthController(AuthService auth, RefreshCookie refreshCookie) {
        this.auth = auth;
        this.refreshCookie = refreshCookie;
    }

    /** The password is only bounded here; its policy is checked in constant time by {@link AuthService}. */
    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(max = 256) String password) {

        public LoginRequest {
            email = Text.normalizeEmail(email);
        }

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=<redacted>]";
        }
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest servletRequest) {
        Session session = auth.login(request.email(), request.password(), servletRequest.getRemoteAddr());
        return sessionResponse(session);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken,
            HttpServletResponse servletResponse) {
        return switch (auth.refresh(refreshToken)) {
            case Rotated rotated -> sessionResponse(rotated.session());
            case Rejected rejected -> {
                servletResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.clear());
                throw ApiException.unauthorized("INVALID_REFRESH_TOKEN",
                        "The refresh token is invalid or expired; sign in again");
            }
        };
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken) {
        auth.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear())
                .build();
    }

    private ResponseEntity<LoginResponse> sessionResponse(Session session) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(session.refreshToken()))
                .body(session.response());
    }
}
