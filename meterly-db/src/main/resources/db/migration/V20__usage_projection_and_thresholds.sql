-- Projection is updated atomically with the immutable ledger. Redis remains disposable.
CREATE TABLE usage_counter_state (
    tenant_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    period_start date NOT NULL,
    total_units bigint NOT NULL CHECK (total_units >= 0),
    ledger_version bigint NOT NULL CHECK (ledger_version >= 0),
    PRIMARY KEY (tenant_id, resource_id, period_start),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES metered_resource (tenant_id, id)
);
CREATE TABLE usage_threshold (
    tenant_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    period_start date NOT NULL,
    threshold_percent integer NOT NULL CHECK (threshold_percent IN (80, 100)),
    event_id uuid NOT NULL,
    PRIMARY KEY (tenant_id, resource_id, period_start, threshold_percent),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES metered_resource (tenant_id, id)
);
ALTER TABLE usage_counter_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON usage_counter_state
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE usage_threshold ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON usage_threshold
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON usage_counter_state TO ${appRole};
GRANT SELECT, INSERT ON usage_threshold TO ${appRole};
