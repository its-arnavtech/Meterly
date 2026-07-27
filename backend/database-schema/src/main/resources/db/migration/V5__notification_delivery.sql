CREATE TABLE notification_delivery (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    payload_summary VARCHAR(512) NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE notification_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_delivery
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

GRANT SELECT, INSERT, UPDATE, DELETE ON notification_delivery TO meterly_app;
