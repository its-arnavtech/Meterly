-- =====================================================================================
-- Refresh-token housekeeping (tenant-service).
--
-- Every token rotation inserts a row, so the table grows with every 15-minute access-token
-- renewal and must be trimmed. The cleanup spans all tenants, which RLS forbids for the
-- application role, so it is a narrow SECURITY DEFINER function like the auth lookups in V3.
--
-- Only tokens that are already past expires_at are removed: a revoked-but-unexpired token must
-- stay so that replaying it is still recognised as reuse and revokes its family. The scan uses
-- refresh_token_expiry_idx (V10).
-- =====================================================================================

CREATE FUNCTION auth_purge_expired_refresh_tokens(p_expired_before timestamptz)
    RETURNS bigint
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    deleted bigint;
BEGIN
    DELETE FROM refresh_token WHERE expires_at < p_expired_before;
    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END
$$;

REVOKE ALL ON FUNCTION auth_purge_expired_refresh_tokens(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_purge_expired_refresh_tokens(timestamptz) TO ${appRole};
