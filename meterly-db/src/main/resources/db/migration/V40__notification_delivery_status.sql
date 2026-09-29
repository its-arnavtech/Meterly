-- =====================================================================================
-- notification-service: claim-before-send delivery with crash recovery.
--
-- Delivery protocol (per recipient of an event):
--   1. claim   INSERT ... status 'PENDING', attempts 1, next_attempt_at = now() + lease
--              ON CONFLICT (tenant_id, event_id, recipient) DO NOTHING RETURNING id
--              -> only a freshly inserted row is sent, so a redelivered Kafka record never
--                 sends a second email.
--   2. send    via the configured email provider (outside any transaction).
--   3. record  'SENT' (sent_at, next_attempt_at NULL) or 'FAILED' (error, next_attempt_at =
--              now() + backoff while attempts < max, otherwise NULL = give up).
--
-- next_attempt_at is the single "needs work" marker: a row with a due next_attempt_at is either a
-- FAILED send waiting for its retry, or a PENDING claim whose lease expired because the process
-- died between claim and send. The retry job re-claims due rows (attempts + 1, new lease) with
-- FOR UPDATE SKIP LOCKED, so several replicas never pick the same row. NULL means terminal.
-- =====================================================================================

ALTER TABLE notification DROP CONSTRAINT notification_status_check;

ALTER TABLE notification
    ADD CONSTRAINT notification_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    ADD COLUMN attempts         int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN sent_at          timestamptz NULL,
    ADD COLUMN next_attempt_at  timestamptz NULL,
    ADD COLUMN updated_at       timestamptz NOT NULL DEFAULT now();

-- Rows written before this migration were attempted exactly once and are terminal.
UPDATE notification SET attempts = 1, sent_at = CASE WHEN status = 'SENT' THEN created_at END;

-- A PENDING claim must always be scheduled, so it can never be stranded by a crash.
ALTER TABLE notification
    ADD CONSTRAINT notification_pending_is_scheduled CHECK (status <> 'PENDING' OR next_attempt_at IS NOT NULL);

CREATE INDEX notification_retry_due_idx ON notification (next_attempt_at) WHERE next_attempt_at IS NOT NULL;

-- Narrow RLS escape hatch for the retry job (same pattern as V3): which tenants have notifications
-- due for another attempt. Returns tenant ids only; the job then works per tenant under RLS.
CREATE FUNCTION notification_retry_due_tenant_ids()
    RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT DISTINCT tenant_id FROM notification WHERE next_attempt_at <= now() ORDER BY tenant_id
$$;

REVOKE ALL ON FUNCTION notification_retry_due_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_retry_due_tenant_ids() TO ${appRole};
