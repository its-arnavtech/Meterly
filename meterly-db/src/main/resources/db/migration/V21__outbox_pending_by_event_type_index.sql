-- =====================================================================================
-- metering-service (V21): outbox relay lookup by event type.
--
-- Each service relays only the event types it owns (metering: usage.threshold_crossed,
-- billing: invoice.issued), so every relay query is
--     WHERE published_at IS NULL AND event_type = ? ORDER BY created_at ... FOR UPDATE SKIP LOCKED
-- The partial index from V1 covered only created_at, which made each relay scan the other
-- service's pending rows as well. The composite partial index below serves both relays, so the
-- V1 index is dropped as redundant (dropping an index never changes query results).
-- =====================================================================================

DROP INDEX IF EXISTS outbox_event_unpublished_idx;

CREATE INDEX outbox_event_unpublished_idx
    ON outbox_event (event_type, created_at)
    WHERE published_at IS NULL;
