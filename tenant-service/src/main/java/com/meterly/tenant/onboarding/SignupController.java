package com.meterly.tenant.onboarding;

import com.meterly.tenant.security.ValidPassword;
import com.meterly.tenant.support.Text;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public tenant signup, throttled per client IP. */
@RestController
public class SignupController {

    private final SignupService signups;

    public SignupController(SignupService signups) {
        this.signups = signups;
    }

    public record SignupRequest(
            @NotBlank @Size(min = 2, max = 100) String tenantName,
            @NotBlank @Email @Size(max = 254) String adminEmail,
            @ValidPassword String adminPassword,
            @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}", message = "must be a plan code such as STARTER") String planCode) {

        public SignupRequest {
            tenantName = Text.strip(tenantName);
            adminEmail = Text.normalizeEmail(adminEmail);
            planCode = Text.strip(planCode);
        }

        @Override
        public String toString() {
            return "SignupRequest[tenantName=" + tenantName + ", adminEmail=" + adminEmail
                    + ", planCode=" + planCode + ", adminPassword=<redacted>]";
        }
    }

    @PostMapping("/v1/tenants")
    public ResponseEntity<SignupService.SignupResult> signup(@Valid @RequestBody SignupRequest request,
                                                             HttpServletRequest servletRequest) {
        SignupService.SignupResult result = signups.signup(
                new SignupService.Signup(request.tenantName(), request.adminEmail(), request.adminPassword(),
                        request.planCode()),
                servletRequest.getRemoteAddr());
        return ResponseEntity.created(URI.create("/v1/tenant")).body(result);
    }
}
