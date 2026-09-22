package com.meterly.tenant.account;

import com.meterly.common.security.CurrentUser;
import com.meterly.tenant.support.Text;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    public record RenameTenantRequest(@NotBlank @Size(min = 2, max = 100) String name) {

        public RenameTenantRequest {
            name = Text.strip(name);
        }
    }

    @GetMapping("/v1/me")
    public AccountService.Me me(CurrentUser caller) {
        return accounts.me(caller);
    }

    @GetMapping("/v1/tenant")
    public Tenant tenant(CurrentUser caller) {
        return accounts.get(caller);
    }

    @PatchMapping("/v1/tenant")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Tenant rename(CurrentUser caller, @Valid @RequestBody RenameTenantRequest request) {
        return accounts.rename(caller, request.name());
    }
}
