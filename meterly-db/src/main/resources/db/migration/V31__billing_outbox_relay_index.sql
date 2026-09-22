-- Each service's outbox relay claims only its own event types
--   (billing: 'invoice.issued', metering: 'usage.threshold_crossed'):
--   SELECT ... FROM outbox_event WHERE published_at IS NULL AND event_type = ? ORDER BY created_at, id
--   LIMIT ? FOR UPDATE SKIP LOCKED
-- This partial index serves that query directly and stays small because published rows drop out of it.
CREATE INDEX outbox_event_type_unpublished_idx ON outbox_event (event_type, created_at, id)
    WHERE published_at IS NULL;
