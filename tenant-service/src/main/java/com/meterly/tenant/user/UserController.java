package com.meterly.tenant.user;

import com.meterly.common.security.CurrentUser;
import com.meterly.common.security.Role;
import com.meterly.tenant.security.ValidPassword;
import com.meterly.tenant.support.Items;
import com.meterly.tenant.support.Text;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class UserController {

    private static final String ROLE_PATTERN = "TENANT_ADMIN|TENANT_USER";

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @ValidPassword String password,
            @NotNull @Pattern(regexp = ROLE_PATTERN, message = "must be TENANT_ADMIN or TENANT_USER") String role) {

        public CreateUserRequest {
            email = Text.normalizeEmail(email);
        }

        @Override
        public String toString() {
            return "CreateUserRequest[email=" + email + ", role=" + role + ", password=<redacted>]";
        }
    }

    /** Partial update: omitted (null) fields are left unchanged. */
    public record UpdateUserRequest(
            @Pattern(regexp = ROLE_PATTERN, message = "must be TENANT_ADMIN or TENANT_USER") String role,
            @Pattern(regexp = "ACTIVE|DISABLED", message = "must be ACTIVE or DISABLED") String status) {
    }

    @GetMapping
    public Items<User> list(CurrentUser caller) {
        return Items.of(users.list(caller));
    }

    @PostMapping
    public ResponseEntity<User> create(CurrentUser caller, @Valid @RequestBody CreateUserRequest request) {
        String passwordHash = users.hashPassword(request.password());
        User created = users.create(caller, request.email(), passwordHash, Role.valueOf(request.role()));
        return ResponseEntity.created(URI.create("/v1/users/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    public User update(CurrentUser caller, @PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(caller, id,
                request.role() == null ? null : Role.valueOf(request.role()),
                request.status() == null ? null : User.UserStatus.valueOf(request.status()));
    }
}
