-- Invoice rendering must not change when the global plan catalog is renamed later.
ALTER TABLE invoice ADD COLUMN plan_code text;
ALTER TABLE invoice ADD COLUMN plan_name text;
UPDATE invoice i SET plan_code = p.code, plan_name = p.name FROM plan p WHERE p.id = i.plan_id;
ALTER TABLE invoice ALTER COLUMN plan_code SET NOT NULL;
ALTER TABLE invoice ALTER COLUMN plan_name SET NOT NULL;
ALTER TABLE invoice_line_item ADD CONSTRAINT invoice_line_resource_tenant_fk
    FOREIGN KEY (tenant_id, resource_id) REFERENCES metered_resource (tenant_id, id);
