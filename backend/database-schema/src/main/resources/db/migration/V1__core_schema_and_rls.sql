DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'meterly_app') THEN
        CREATE ROLE meterly_app LOGIN PASSWORD 'meterly-app-local'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

CREATE TABLE tenant (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    email VARCHAR(320) NOT NULL,
    role VARCHAR(30) NOT NULL CHECK (role IN ('TENANT_ADMIN', 'TENANT_USER')),
    password_hash VARCHAR(100) NOT NULL,
    UNIQUE (tenant_id, email)
);

CREATE UNIQUE INDEX app_user_email_ci_uidx ON app_user (lower(email));

CREATE TABLE plan (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    base_price NUMERIC(19, 2) NOT NULL CHECK (base_price >= 0),
    included_units BIGINT NOT NULL CHECK (included_units >= 0)
);

CREATE TABLE pricing_tier (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES plan(id),
    from_unit BIGINT NOT NULL CHECK (from_unit > 0),
    to_unit BIGINT,
    unit_price NUMERIC(19, 6) NOT NULL CHECK (unit_price >= 0),
    CHECK (to_unit IS NULL OR to_unit >= from_unit),
    UNIQUE (plan_id, from_unit)
);

CREATE TABLE subscription (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    plan_id UUID NOT NULL REFERENCES plan(id),
    start_date DATE NOT NULL,
    end_date DATE,
    status VARCHAR(30) NOT NULL CHECK (status IN ('ACTIVE', 'ENDED')),
    CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE TABLE metered_resource (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    name VARCHAR(120) NOT NULL,
    unit_label VARCHAR(40) NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE api_key (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    label VARCHAR(120) NOT NULL,
    key_hash CHAR(64) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE usage_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    resource_id UUID NOT NULL,
    idempotency_key UUID NOT NULL UNIQUE,
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES metered_resource(tenant_id, id)
);

CREATE INDEX usage_event_period_idx ON usage_event (tenant_id, resource_id, occurred_at);

CREATE TABLE usage_period_aggregate (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    resource_id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_units BIGINT NOT NULL CHECK (total_units >= 0),
    UNIQUE (tenant_id, resource_id, period_start),
    CHECK (period_end > period_start),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES metered_resource(tenant_id, id)
);

CREATE TABLE invoice (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('DRAFT', 'FINALIZED')),
    total_amount NUMERIC(19, 2) NOT NULL CHECK (total_amount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, period_start),
    CHECK (period_end > period_start)
);

CREATE TABLE invoice_line_item (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    invoice_id UUID NOT NULL,
    description VARCHAR(240) NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    unit_price NUMERIC(19, 6) NOT NULL CHECK (unit_price >= 0),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount >= 0),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice(tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'app_user', 'subscription', 'metered_resource', 'api_key', 'usage_event',
        'usage_period_aggregate', 'invoice', 'invoice_line_item'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::UUID) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::UUID)',
            table_name
        );
    END LOOP;
END
$$;

CREATE FUNCTION auth_lookup_user(p_email TEXT)
RETURNS TABLE (tenant_id UUID, email VARCHAR, role VARCHAR, password_hash VARCHAR)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT u.tenant_id, u.email, u.role, u.password_hash
    FROM app_user u
    JOIN tenant t ON t.id = u.tenant_id
    WHERE lower(u.email) = lower(p_email)
      AND t.status = 'ACTIVE'
    LIMIT 1
$$;

CREATE FUNCTION api_key_lookup_tenant(p_key_hash TEXT)
RETURNS UUID
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT k.tenant_id
    FROM api_key k
    JOIN tenant t ON t.id = k.tenant_id
    WHERE k.key_hash = p_key_hash
      AND k.active
      AND t.status = 'ACTIVE'
    LIMIT 1
$$;

REVOKE ALL ON FUNCTION auth_lookup_user(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION api_key_lookup_tenant(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_lookup_user(TEXT) TO meterly_app;
GRANT EXECUTE ON FUNCTION api_key_lookup_tenant(TEXT) TO meterly_app;

GRANT USAGE ON SCHEMA public TO meterly_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO meterly_app;

INSERT INTO plan (id, name, base_price, included_units)
VALUES
    ('22222222-2222-2222-2222-222222222222', 'Developer', 49.00, 1000),
    ('22222222-2222-2222-2222-222222222223', 'Growth', 99.00, 5000),
    ('22222222-2222-2222-2222-222222222224', 'Scale', 249.00, 20000);

INSERT INTO pricing_tier (id, plan_id, from_unit, to_unit, unit_price)
VALUES
    ('33333333-3333-3333-3333-333333333331', '22222222-2222-2222-2222-222222222222', 1001, NULL, 0.020000),
    ('33333333-3333-3333-3333-333333333332', '22222222-2222-2222-2222-222222222223', 5001, 10000, 0.015000),
    ('33333333-3333-3333-3333-333333333333', '22222222-2222-2222-2222-222222222223', 10001, NULL, 0.010000),
    ('33333333-3333-3333-3333-333333333334', '22222222-2222-2222-2222-222222222224', 20001, 50000, 0.008000),
    ('33333333-3333-3333-3333-333333333335', '22222222-2222-2222-2222-222222222224', 50001, NULL, 0.005000);

INSERT INTO tenant (id, name, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'Meterly Demo', 'ACTIVE');

INSERT INTO app_user (id, tenant_id, email, role, password_hash)
VALUES (
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    '11111111-1111-1111-1111-111111111111',
    'admin@meterly.local',
    'TENANT_ADMIN',
    '$2a$10$V8nf.aok3L2L51ql74UrY.yRNvI4C73kpcMutnji12hrPcuPOhYmO'
);

INSERT INTO subscription (id, tenant_id, plan_id, start_date, status)
VALUES (
    '44444444-4444-4444-4444-444444444444',
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    DATE '2026-07-01',
    'ACTIVE'
);

INSERT INTO metered_resource (id, tenant_id, name, unit_label)
VALUES (
    '55555555-5555-5555-5555-555555555555',
    '11111111-1111-1111-1111-111111111111',
    'API calls',
    'call'
);

INSERT INTO api_key (id, tenant_id, label, key_hash)
VALUES (
    '66666666-6666-6666-6666-666666666666',
    '11111111-1111-1111-1111-111111111111',
    'Local demo key',
    '24d2469c28944edc3ed385e45cb9446daaa1f9a06a0f1932601d63695f02fdc0'
);
