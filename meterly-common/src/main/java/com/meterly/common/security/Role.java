package com.meterly.common.security;

/** Tenant-level roles. TENANT_ADMIN manages plan/billing/keys/users; TENANT_USER sees the usage dashboard. */
public enum Role {
    TENANT_ADMIN,
    TENANT_USER;

    /** Spring Security authority string, e.g. {@code ROLE_TENANT_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
