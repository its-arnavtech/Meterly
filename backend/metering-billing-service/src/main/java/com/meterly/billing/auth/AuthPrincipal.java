package com.meterly.billing.auth;

import java.util.UUID;

public record AuthPrincipal(String email, UUID tenantId, String role) {
}
