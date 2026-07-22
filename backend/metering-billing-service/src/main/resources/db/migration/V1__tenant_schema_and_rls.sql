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
    UNIQUE (tenant_id, name)
);

CREATE TABLE usage_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    resource_id UUID NOT NULL REFERENCES metered_resource(id),
    idempotency_key UUID NOT NULL UNIQUE,
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX usage_event_period_idx ON usage_event (tenant_id, resource_id, occurred_at);

CREATE TABLE usage_period_aggregate (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    resource_id UUID NOT NULL REFERENCES metered_resource(id),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_units BIGINT NOT NULL CHECK (total_units >= 0),
    UNIQUE (tenant_id, resource_id, period_start),
    CHECK (period_end > period_start)
);

CREATE TABLE invoice (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('DRAFT', 'FINALIZED')),
    total_amount NUMERIC(19, 2) NOT NULL CHECK (total_amount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, period_start),
    CHECK (period_end > period_start)
);

CREATE TABLE invoice_line_item (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    invoice_id UUID NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    description VARCHAR(240) NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    unit_price NUMERIC(19, 6) NOT NULL CHECK (unit_price >= 0),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount >= 0)
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
        'app_user', 'subscription', 'metered_resource', 'usage_event',
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

GRANT USAGE ON SCHEMA public TO meterly_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO meterly_app;

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

INSERT INTO plan (id, name, base_price, included_units)
VALUES ('22222222-2222-2222-2222-222222222222', 'Developer', 49.00, 1000);

INSERT INTO pricing_tier (id, plan_id, from_unit, to_unit, unit_price)
VALUES (
    '33333333-3333-3333-3333-333333333333',
    '22222222-2222-2222-2222-222222222222',
    1001,
    NULL,
    0.020000
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
