CREATE FUNCTION system_active_tenant_ids()
RETURNS SETOF UUID
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT id
    FROM tenant
    WHERE status = 'ACTIVE'
    ORDER BY id
$$;

REVOKE ALL ON FUNCTION system_active_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION system_active_tenant_ids() TO meterly_app;
