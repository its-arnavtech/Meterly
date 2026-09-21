package com.meterly.tenant;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final AuthService auth;
    private final AuthThrottle throttle;
    private final TokenIssuer issuer;
    private final boolean secure;
    public AuthController(AuthService auth,AuthThrottle throttle,TokenIssuer issuer,Environment environment) {
        this.auth=auth; this.throttle=throttle; this.issuer=issuer;
        secure=environment.getProperty("meterly.auth.refresh-cookie-secure",Boolean.class,false);
    }
    @PostMapping("/v1/auth/login")
    public Map<String,Object> login(@Valid @RequestBody TenantRequests.Login input,HttpServletRequest request,HttpServletResponse response) {
        throttle.login(request.getRemoteAddr(),input.email(),response);
        return respond(auth.login(input.email(),input.password()),response);
    }
    @PostMapping("/v1/auth/refresh")
    public Map<String,Object> refresh(@CookieValue(name="meterly_refresh",required=false) String token,
                                     HttpServletRequest request,HttpServletResponse response) {
        throttle.refresh(request.getRemoteAddr(),response);
        return respond(auth.refresh(token),response);
    }
    @PostMapping("/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name="meterly_refresh",required=false) String token,HttpServletResponse response) {
        auth.logout(token); cookie(response,"",Duration.ZERO);
    }
    @GetMapping("/.well-known/jwks.json")
    public Map<String,Object> jwks() { return issuer.jwks(); }
    private Map<String,Object> respond(AuthService.Session session,HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        response.setHeader(HttpHeaders.PRAGMA,"no-cache");
        cookie(response,session.refreshToken(),Duration.ofDays(7));
        return session.body();
    }
    private void cookie(HttpServletResponse response,String value,Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("meterly_refresh",value)
                .httpOnly(true).secure(secure).sameSite("Strict").path("/v1/auth").maxAge(age).build().toString());
    }
}
