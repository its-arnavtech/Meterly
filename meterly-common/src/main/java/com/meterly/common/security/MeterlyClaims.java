package com.meterly.common.security;

/** JWT claim names and fixed token metadata shared by the issuer (tenant-service) and all validators. */
public final class MeterlyClaims {

    public static final String TENANT_ID = "tenant_id";
    public static final String ROLE = "role";
    public static final String EMAIL = "email";

    /** Default {@code iss} claim; overridable via {@code meterly.security.jwt.issuer}. */
    public static final String DEFAULT_ISSUER = "meterly";
    /** Default {@code aud} claim; overridable via {@code meterly.security.jwt.audience}. */
    public static final String DEFAULT_AUDIENCE = "meterly-api";

    private MeterlyClaims() {
    }
}
