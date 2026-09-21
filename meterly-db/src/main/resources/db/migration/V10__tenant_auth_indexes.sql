-- Keep refresh session checks and user invalidation bounded as session history grows.
CREATE INDEX refresh_token_user_active_idx ON refresh_token (tenant_id, user_id)
    WHERE revoked_at IS NULL;
CREATE INDEX refresh_token_expiry_idx ON refresh_token (expires_at);
CREATE INDEX subscription_current_idx ON subscription (tenant_id, start_date, end_date)
    WHERE status = 'ACTIVE';
