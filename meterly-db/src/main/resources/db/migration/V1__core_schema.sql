-- =====================================================================================
-- Meterly core schema (PDD section 3).
--
-- Conventions
--   * Every tenant-scoped table carries tenant_id and is protected by RLS (V2).
--   * Child tables reference parents through composite (tenant_id, id) foreign keys, so a
--     row can never point at another tenant's parent. This matters because PostgreSQL's
--     referential-integrity checks bypass RLS.
--   * Money: numeric(19,2) for amounts, numeric(19,6) for unit prices. Never float.
--   * Timestamps are timestamptz (UTC); billing periods are [period_start, period_end) dates.
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------- tenants & users
CREATE TABLE tenant (
    id          uuid        PRIMARY KEY,
    name        text        NOT NULL CHECK (char_length(name) BETWEEN 2 AND 100),
    status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    email          text        NOT NULL CHECK (char_length(email) BETWEEN 3 AND 254),
    role           text        NOT NULL CHECK (role IN ('TENANT_ADMIN', 'TENANT_USER')),
    password_hash  text        NOT NULL,
    status         text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
-- Emails are globally unique (login is by email alone). Unique indexes are enforced regardless of RLS.
CREATE UNIQUE INDEX app_user_email_uq ON app_user (lower(email));
CREATE INDEX app_user_tenant_idx ON app_user (tenant_id);

CREATE TABLE refresh_token (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    token_hash   text        NOT NULL UNIQUE,
    family_id    uuid        NOT NULL,
    expires_at   timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    revoked_at   timestamptz NULL,
    replaced_by  uuid        NULL,
    FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id)
);
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);

-- ---------------------------------------------------------------- plan catalog (global, not tenant-scoped)
CREATE TABLE plan (
    id              uuid          PRIMARY KEY,
    code            text          NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    name            text          NOT NULL,
    base_price      numeric(19,2) NOT NULL CHECK (base_price >= 0),
    included_units  bigint        NOT NULL CHECK (included_units >= 0),
    currency        char(3)       NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    active          boolean       NOT NULL DEFAULT true,
    sort_order      int           NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now()
);

-- A tier prices the cumulative units in the half-open range (from_unit, to_unit]:
--   units_in_tier = max(0, min(total_units, coalesce(to_unit, infinity)) - from_unit)
-- The first tier starts at plan.included_units, tiers are contiguous, the last has to_unit NULL.
CREATE TABLE pricing_tier (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id     uuid          NOT NULL REFERENCES plan (id),
    from_unit   bigint        NOT NULL CHECK (from_unit >= 0),
    to_unit     bigint        NULL,
    unit_price  numeric(19,6) NOT NULL CHECK (unit_price >= 0),
    CHECK (to_unit IS NULL OR to_unit > from_unit),
    UNIQUE (plan_id, from_unit)
);

-- ---------------------------------------------------------------- subscriptions
-- Plan changes take effect at the next period boundary (mid-period proration is out of scope),
-- so at most one ACTIVE subscription covers any given day. The exclusion constraint enforces it.
CREATE TABLE subscription (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    plan_id     uuid        NOT NULL REFERENCES plan (id),
    start_date  date        NOT NULL,
    end_date    date        NULL,
    status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (end_date IS NULL OR end_date > start_date),
    CONSTRAINT subscription_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        daterange(start_date, end_date, '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);
CREATE INDEX subscription_tenant_idx ON subscription (tenant_id, start_date);

-- ---------------------------------------------------------------- metered resources & API keys
CREATE TABLE metered_resource (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    name        text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    unit_label  text        NOT NULL CHECK (char_length(unit_label) BETWEEN 1 AND 30),
    status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE TABLE api_key (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    name          text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    prefix        text        NOT NULL,
    key_hash      text        NOT NULL UNIQUE,
    created_by    uuid        NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at  timestamptz NULL,
    revoked_at    timestamptz NULL
);
CREATE INDEX api_key_tenant_idx ON api_key (tenant_id);

-- ---------------------------------------------------------------- usage ledger (append-only; source of truth)
CREATE TABLE usage_event (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    resource_id      uuid        NOT NULL,
    idempotency_key  text        NOT NULL CHECK (char_length(idempotency_key) BETWEEN 1 AND 255),
    quantity         bigint      NOT NULL CHECK (quantity > 0),
    occurred_at      timestamptz NOT NULL,
    received_at      timestamptz NOT NULL,
    recorded_at      timestamptz NOT NULL DEFAULT now(),
    -- Redelivered Kafka messages and client retries collapse onto this constraint:
    -- INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO NOTHING.
    CONSTRAINT usage_event_idempotency_uq UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES metered_resource (tenant_id, id)
);
CREATE INDEX usage_event_period_idx ON usage_event (tenant_id, resource_id, occurred_at);

CREATE TABLE usage_period_aggregate (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL,
    resource_id   uuid        NOT NULL,
    period_start  date        NOT NULL,
    period_end    date        NOT NULL,
    total_units   bigint      NOT NULL CHECK (total_units >= 0),
    computed_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end > period_start),
    UNIQUE (tenant_id, resource_id, period_start),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES metered_resource (tenant_id, id)
);

-- ---------------------------------------------------------------- invoices
CREATE SEQUENCE invoice_number_seq;

CREATE TABLE invoice (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    invoice_number  text          NOT NULL UNIQUE,
    plan_id         uuid          NOT NULL REFERENCES plan (id),
    period_start    date          NOT NULL,
    period_end      date          NOT NULL,
    status          text          NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED', 'VOID')),
    currency        char(3)       NOT NULL DEFAULT 'USD',
    total_amount    numeric(19,2) NOT NULL CHECK (total_amount >= 0),
    issued_at       timestamptz   NOT NULL DEFAULT now(),
    CHECK (period_end > period_start),
    -- Billing idempotency: re-running a period-close job can never produce a second invoice.
    CONSTRAINT invoice_tenant_period_uq UNIQUE (tenant_id, period_start),
    UNIQUE (tenant_id, id)
);

CREATE TABLE invoice_line_item (
    id           uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid          NOT NULL,
    invoice_id   uuid          NOT NULL,
    line_number  int           NOT NULL CHECK (line_number > 0),
    resource_id  uuid          NULL,
    description  text          NOT NULL,
    quantity     bigint        NOT NULL CHECK (quantity >= 0),
    unit_price   numeric(19,6) NOT NULL CHECK (unit_price >= 0),
    amount       numeric(19,2) NOT NULL CHECK (amount >= 0),
    UNIQUE (invoice_id, line_number),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice (tenant_id, id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------- notifications
CREATE TABLE notification (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    event_id    uuid        NOT NULL,
    type        text        NOT NULL,
    recipient   text        NOT NULL,
    subject     text        NOT NULL,
    body        text        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('SENT', 'FAILED')),
    error       text        NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Consumer idempotency: a redelivered event never sends a second email to the same recipient.
    CONSTRAINT notification_event_recipient_uq UNIQUE (tenant_id, event_id, recipient)
);
CREATE INDEX notification_tenant_idx ON notification (tenant_id, created_at DESC);

-- ---------------------------------------------------------------- system tables (not tenant-facing, no RLS)
-- Transactional outbox: rows are written in the same transaction as the business change and relayed
-- to Kafka afterwards, so an event is published if and only if the change committed.
CREATE TABLE outbox_event (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    topic         text        NOT NULL,
    event_key     text        NOT NULL,
    event_type    text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz NULL,
    attempts      int         NOT NULL DEFAULT 0,
    last_error    text        NULL
);
CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;

-- ShedLock: cluster-wide mutual exclusion for scheduled jobs (billing close, reconciliation, outbox relay).
CREATE TABLE shedlock (
    name        varchar(64)  PRIMARY KEY,
    lock_until  timestamp(3) NOT NULL,
    locked_at   timestamp(3) NOT NULL,
    locked_by   varchar(255) NOT NULL
);
