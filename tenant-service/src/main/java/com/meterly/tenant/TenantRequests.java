package com.meterly.tenant;

import jakarta.validation.constraints.*;

/** Input records keep validation at the HTTP boundary; passwords also have a UTF-8 byte limit. */
public final class TenantRequests {
    private TenantRequests() {}
    public record Signup(@NotBlank @Size(min=2,max=100) String tenantName,
                         @NotBlank @Email @Size(max=254) String adminEmail,
                         @NotBlank @Size(min=10,max=72) String adminPassword,
                         @Pattern(regexp="[A-Z][A-Z0-9_]{1,31}") String planCode) {}
    public record Login(@NotBlank @Email @Size(max=254) String email,
                        @NotBlank @Size(max=72) String password) {}
    public record TenantPatch(@NotBlank @Size(min=2,max=100) String name) {}
    public record PlanChange(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,31}") String planCode) {}
    public record ResourceCreate(@NotBlank @Size(max=100) String name,
                                 @NotBlank @Size(max=30) String unitLabel) {}
    public record ResourcePatch(@Size(min=1,max=100) @Pattern(regexp=".*\\S.*") String name,
                                @Size(min=1,max=30) @Pattern(regexp=".*\\S.*") String unitLabel,
                                @Pattern(regexp="ACTIVE|ARCHIVED") String status) {}
    public record UserCreate(@NotBlank @Email @Size(max=254) String email,
                             @NotBlank @Size(min=10,max=72) String password,
                             @NotNull @Pattern(regexp="TENANT_ADMIN|TENANT_USER") String role) {}
    public record UserPatch(@Pattern(regexp="TENANT_ADMIN|TENANT_USER") String role,
                            @Pattern(regexp="ACTIVE|DISABLED") String status) {}
    public record KeyCreate(@NotBlank @Size(max=100) String name) {}
    public record KeyVerify(@NotBlank @Size(max=128) String apiKey) {}
}
