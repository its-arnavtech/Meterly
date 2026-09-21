-- =====================================================================================
-- Demo data (only applied when the migrator runs with the demo location enabled).
-- Idempotent: fixed ids + ON CONFLICT DO NOTHING. Dates are relative to the first run.
--
--   Tenant       Login                 Password       Plan      API key
--   Acme Corp    admin@acme.test       Meterly!2026   STARTER   mk_demo_acme_2Yh8Qe4LxN7vR1tK9pWz3cJ5
--                viewer@acme.test      Meterly!2026   (TENANT_USER, dashboard only)
--   Globex       admin@globex.test     Meterly!2026   GROWTH    mk_demo_globex_6Bn3Df8MsQ1wT5yH7kLr9aE2
--
-- Previous-month usage and the hand-calculated invoice each tenant should receive:
--   Acme   API calls 150,000 ; Webhooks 12,345 (STARTER: 10,000 included per resource)
--          19.00 + (90,000 x 0.002 + 50,000 x 0.0015) + (2,345 x 0.002)
--          = 19.00 + 180.00 + 75.00 + 4.69                                   = 278.69 USD
--   Globex API calls 250,000 (GROWTH: 100,000 included)
--          99.00 + 150,000 x 0.001                                           = 249.00 USD
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$
DECLARE
    acme      constant uuid := '11111111-1111-4111-8111-111111111111';
    globex    constant uuid := '22222222-2222-4222-8222-222222222222';
    acme_api  constant uuid := 'a1111111-0000-4000-8000-000000000001';
    acme_hook constant uuid := 'a1111111-0000-4000-8000-000000000002';
    globex_api constant uuid := 'b2222222-0000-4000-8000-000000000001';
    starter   constant uuid := '0a000000-0000-4000-8000-000000000001';
    growth    constant uuid := '0a000000-0000-4000-8000-000000000002';
    cur_start timestamptz := date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    prev_start timestamptz := (date_trunc('month', now() AT TIME ZONE 'UTC') - interval '1 month') AT TIME ZONE 'UTC';
    pw text := crypt('Meterly!2026', gen_salt('bf', 10));
BEGIN
    INSERT INTO tenant (id, name) VALUES (acme, 'Acme Corp'), (globex, 'Globex')
    ON CONFLICT DO NOTHING;

    INSERT INTO app_user (id, tenant_id, email, role, password_hash) VALUES
        ('c1111111-0000-4000-8000-000000000001', acme,   'admin@acme.test',   'TENANT_ADMIN', pw),
        ('c1111111-0000-4000-8000-000000000002', acme,   'viewer@acme.test',  'TENANT_USER',  pw),
        ('c2222222-0000-4000-8000-000000000001', globex, 'admin@globex.test', 'TENANT_ADMIN', pw)
    ON CONFLICT DO NOTHING;

    INSERT INTO metered_resource (id, tenant_id, name, unit_label) VALUES
        (acme_api,   acme,   'API calls', 'call'),
        (acme_hook,  acme,   'Webhooks delivered', 'delivery'),
        (globex_api, globex, 'API calls', 'call')
    ON CONFLICT DO NOTHING;

    INSERT INTO subscription (id, tenant_id, plan_id, start_date) VALUES
        ('d1111111-0000-4000-8000-000000000001', acme,   starter, prev_start::date),
        ('d2222222-0000-4000-8000-000000000001', globex, growth,  prev_start::date)
    ON CONFLICT DO NOTHING;

    INSERT INTO api_key (id, tenant_id, name, prefix, key_hash) VALUES
        ('e1111111-0000-4000-8000-000000000001', acme, 'Demo key', 'mk_demo_acme',
         encode(digest('mk_demo_acme_2Yh8Qe4LxN7vR1tK9pWz3cJ5', 'sha256'), 'hex')),
        ('e2222222-0000-4000-8000-000000000001', globex, 'Demo key', 'mk_demo_glob',
         encode(digest('mk_demo_globex_6Bn3Df8MsQ1wT5yH7kLr9aE2', 'sha256'), 'hex'))
    ON CONFLICT DO NOTHING;

    -- Previous month: Acme API calls 150 x 1,000 = 150,000
    INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at)
    SELECT acme, acme_api, 'demo-acme-api-prev-' || n, 1000,
           prev_start + n * interval '4 hours', prev_start + n * interval '4 hours'
    FROM generate_series(1, 150) AS n
    ON CONFLICT DO NOTHING;

    -- Previous month: Acme webhooks 5 x 2,469 = 12,345
    INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at)
    SELECT acme, acme_hook, 'demo-acme-hook-prev-' || n, 2469,
           prev_start + n * interval '3 days', prev_start + n * interval '3 days'
    FROM generate_series(1, 5) AS n
    ON CONFLICT DO NOTHING;

    -- Previous month: Globex API calls 250 x 1,000 = 250,000
    INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at)
    SELECT globex, globex_api, 'demo-globex-api-prev-' || n, 1000,
           prev_start + n * interval '2 hours', prev_start + n * interval '2 hours'
    FROM generate_series(1, 250) AS n
    ON CONFLICT DO NOTHING;

    -- Current month so far: Acme API calls 42 x 100 = 4,200 (dashboard starts non-empty)
    INSERT INTO usage_event (tenant_id, resource_id, idempotency_key, quantity, occurred_at, received_at)
    SELECT acme, acme_api, 'demo-acme-api-cur-' || n, 100,
           cur_start + n * interval '1 second', cur_start + n * interval '1 second'
    FROM generate_series(1, 42) AS n
    ON CONFLICT DO NOTHING;
END
$$;
