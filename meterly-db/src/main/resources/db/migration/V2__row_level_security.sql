-- =====================================================================================
-- Tenant isolation via PostgreSQL Row-Level Security (PDD section 4).
--
-- The application connects as ${appRole}: not a superuser, not the table owner, NOBYPASSRLS, so
-- every policy below applies to it. The tenant is supplied per transaction by the application:
--     SELECT set_config('app.tenant_id', '<uuid>', true);   -- is_local = true  ==  SET LOCAL
-- which reverts at COMMIT/ROLLBACK and therefore cannot leak across pooled connections.
--
-- NULLIF(..., '') makes an unset or cleared setting evaluate to NULL, and `tenant_id = NULL` is never
-- true: a query issued without a tenant sees zero rows and cannot insert anything (fail closed).
-- =====================================================================================

-- tenant: the row's own id is the tenant id
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'app_user', 'refresh_token', 'subscription', 'metered_resource', 'api_key',
        'usage_event', 'usage_period_aggregate', 'invoice', 'invoice_line_item', 'notification'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I '
            'USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) '
            'WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------- privileges for the application role
GRANT USAGE ON SCHEMA public TO ${appRole};

GRANT SELECT, INSERT, UPDATE ON tenant, app_user, metered_resource, api_key,
    usage_period_aggregate, invoice, invoice_line_item, notification TO ${appRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON subscription, refresh_token TO ${appRole};

-- The usage ledger is append-only for the application: no UPDATE, no DELETE.
GRANT SELECT, INSERT ON usage_event TO ${appRole};

-- The plan catalog is read-only for the application (managed through migrations).
GRANT SELECT ON plan, pricing_tier TO ${appRole};

-- System tables.
GRANT SELECT, INSERT, UPDATE, DELETE ON outbox_event, shedlock TO ${appRole};

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${appRole};
