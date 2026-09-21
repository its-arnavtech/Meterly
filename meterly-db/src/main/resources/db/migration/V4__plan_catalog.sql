-- =====================================================================================
-- Plan catalog. Tier semantics (see pricing_tier in V1): a tier prices cumulative units in
-- (from_unit, to_unit]; the first tier starts at included_units; the last tier is open-ended.
--
-- Worked example (STARTER, 150,000 API calls in the period):
--   base fee                                   19.00
--   units      1 –  10,000 (included)            0.00
--   units 10,001 – 100,000: 90,000 x 0.002000  180.00
--   units 100,001 – 150,000: 50,000 x 0.001500  75.00
--   total                                      274.00
-- =====================================================================================

INSERT INTO plan (id, code, name, base_price, included_units, currency, sort_order) VALUES
    ('0a000000-0000-4000-8000-000000000001', 'STARTER', 'Starter',  19.00,    10000, 'USD', 1),
    ('0a000000-0000-4000-8000-000000000002', 'GROWTH',  'Growth',   99.00,   100000, 'USD', 2),
    ('0a000000-0000-4000-8000-000000000003', 'SCALE',   'Scale',   499.00,  1000000, 'USD', 3);

INSERT INTO pricing_tier (plan_id, from_unit, to_unit, unit_price) VALUES
    ('0a000000-0000-4000-8000-000000000001',    10000,   100000, 0.002000),
    ('0a000000-0000-4000-8000-000000000001',   100000,     NULL, 0.001500),
    ('0a000000-0000-4000-8000-000000000002',   100000,  1000000, 0.001000),
    ('0a000000-0000-4000-8000-000000000002',  1000000,     NULL, 0.000700),
    ('0a000000-0000-4000-8000-000000000003',  1000000, 10000000, 0.000500),
    ('0a000000-0000-4000-8000-000000000003', 10000000,     NULL, 0.000300);
