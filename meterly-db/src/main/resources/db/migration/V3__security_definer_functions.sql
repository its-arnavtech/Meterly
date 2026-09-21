-- =====================================================================================
-- Narrow, audited RLS escape hatches.
--
-- A handful of lookups must happen *before* the tenant is known: login by email, API-key
-- authentication, refresh-token exchange, and schedulers enumerating tenants. Rather than giving the
-- application a BYPASSRLS role, each lookup is a SECURITY DEFINER function owned by the migration
-- role (the table owner, to which RLS does not apply). The application may only EXECUTE them; each
-- returns just the columns needed to then bind the tenant and continue under RLS.
-- =====================================================================================

CREATE FUNCTION auth_find_user_by_email(p_email text)
    RETURNS TABLE (user_id uuid, tenant_id uuid, email text, role text, password_hash text,
                   user_status text, tenant_status text, tenant_name text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT u.id, u.tenant_id, u.email, u.role, u.password_hash, u.status, t.status, t.name
    FROM app_user u
    JOIN tenant t ON t.id = u.tenant_id
    WHERE lower(u.email) = lower(p_email)
$$;

CREATE FUNCTION auth_find_api_key(p_key_hash text)
    RETURNS TABLE (api_key_id uuid, tenant_id uuid, revoked boolean, tenant_status text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT k.id, k.tenant_id, k.revoked_at IS NOT NULL, t.status
    FROM api_key k
    JOIN tenant t ON t.id = k.tenant_id
    WHERE k.key_hash = p_key_hash
$$;

CREATE FUNCTION auth_find_refresh_token(p_token_hash text)
    RETURNS TABLE (token_id uuid, tenant_id uuid, user_id uuid, family_id uuid,
                   expires_at timestamptz, revoked_at timestamptz, replaced_by uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT r.id, r.tenant_id, r.user_id, r.family_id, r.expires_at, r.revoked_at, r.replaced_by
    FROM refresh_token r
    WHERE r.token_hash = p_token_hash
$$;

-- Tenant enumeration for cluster-wide jobs (billing close, reconciliation). Returns ids only.
CREATE FUNCTION system_active_tenant_ids()
    RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT id FROM tenant WHERE status = 'ACTIVE' ORDER BY id
$$;

REVOKE ALL ON FUNCTION auth_find_user_by_email(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_find_api_key(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth_find_refresh_token(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION system_active_tenant_ids() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION auth_find_user_by_email(text) TO ${appRole};
GRANT EXECUTE ON FUNCTION auth_find_api_key(text) TO ${appRole};
GRANT EXECUTE ON FUNCTION auth_find_refresh_token(text) TO ${appRole};
GRANT EXECUTE ON FUNCTION system_active_tenant_ids() TO ${appRole};
