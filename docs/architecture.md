# Meterly architecture

This is the "how it actually works" companion to [`docs/api-contract.md`](api-contract.md) (the
binding request/response contract) and the original design docs
([`Meterly_PDD.txt`](Meterly_PDD.txt), [`Meterly_POC.txt`](Meterly_POC.txt)).

## End-to-end flow

1. **Signup** (`POST /v1/tenants`, tenant-service): creates a tenant, a `TENANT_ADMIN` user, a
   subscription on the requested plan starting today, and one default metered resource. Public,
   throttled per client IP.
2. **Login** (`POST /v1/auth/login`): issues a short-lived (15 min) RS256 JWT carrying `tenant_id` and
   `role`, plus an `HttpOnly` refresh cookie scoped to `/v1/auth`. Every other service validates the
   JWT against tenant-service's JWKS (`/.well-known/jwks.json`) — no service-to-service session state.
3. **Usage ingestion** (`POST /v1/usage-events`, ingestion-service): authenticates via `X-API-Key` (a
   machine credential, verified against tenant-service over `/internal/v1/api-keys/verify`) or a
   `TENANT_ADMIN` JWT. Checks a per-tenant Redis token bucket, checks a Redis idempotency record, then
   publishes a `UsageEventMessage` to the `usage-events` Kafka topic (key = `tenant_id`) and only
   *then* — after the broker acknowledges — writes the idempotency record. The event is **not yet
   counted**; ingestion's job is admission control and durable handoff, not the ledger.
4. **Metering** (metering-service, Kafka consumer group `metering-service`): for each record, one
   transaction inserts into the append-only `usage_event` ledger with
   `ON CONFLICT (tenant_id, idempotency_key) DO NOTHING`, and — only if a row was actually inserted —
   updates a transactional projection (`usage_counter_state`) and evaluates usage thresholds, all in
   the same transaction. After commit, the new counter is projected into Redis with a **version-guarded**
   Lua script and broadcast on a Redis pub/sub channel; every metering-service instance forwards that
   to its own connected STOMP clients, so a browser connected to *any* instance sees every update.
5. **Live dashboard**: the Angular SPA opens a native WebSocket to `/ws/usage`, speaks STOMP directly
   (`CONNECT` with a bearer JWT, `SUBSCRIBE /topic/tenants/{tenant_id}/usage`), and receives a
   `UsageUpdate` MESSAGE frame within roughly the ingestion-to-Kafka-to-consumer-to-Redis-to-pub/sub
   round trip — measured by `meterly-validation`'s STOMP latency check, not asserted.
6. **Billing** (billing-service, monthly cron + `POST /v1/billing/run`): for a **closed** period, one
   tenant-bound transaction re-sums the ledger (never Redis — the ledger is the only source of truth
   for money), prices it against the tenant's plan tiers, and inserts one invoice
   (`ON CONFLICT (tenant_id, period_start) DO NOTHING` — re-running an already-billed period is a
   no-op that returns the existing invoice). An `InvoiceIssued` event goes to the `outbox_event` table
   in the *same* transaction as the invoice.
7. **Notifications** (notification-service, Kafka consumer group on `notification-events`): renders
   and sends an email per event, recording `(tenant_id, event_id, recipient)` with
   `ON CONFLICT DO NOTHING` **before** sending, so a redelivered event never double-sends.

## Tenant isolation: PostgreSQL Row-Level Security

Every tenant-scoped table carries `tenant_id` and an RLS policy
(`tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid`, see
`meterly-db/src/main/resources/db/migration/V2__row_level_security.sql`). Two properties make this a
real guarantee rather than an application convention:

- **Services connect as `meterly_app`** — `NOSUPERUSER NOBYPASSRLS`, not the table owner
  (`meterly_owner`, who runs migrations and is exempt from RLS by definition). RLS cannot be
  accidentally bypassed by a query that forgets a `WHERE` clause, because the database enforces it
  independently of what SQL the application wrote.
- **The tenant is bound per transaction, not per connection.** `TenantAwareTransactionManager`
  overrides Spring's transaction preparation hook to run `SELECT set_config('app.tenant_id', ?, true)`
  (`true` = local to the transaction, i.e. `SET LOCAL`) immediately after `BEGIN`, on the exact pooled
  connection the transaction will use. It reverts automatically at `COMMIT`/`ROLLBACK`, so it can never
  leak to the next tenant that borrows the same connection from the pool. `NULLIF(..., '')` turns an
  unset or cleared setting into `NULL`, and `tenant_id = NULL` is never true — a query with no tenant
  bound sees zero rows and cannot insert anything (fail closed, not fail open).
- Outside an HTTP request (Kafka consumers, `@Scheduled` jobs) there is no request to bind the tenant
  from, so those call sites use `TenantContext.runAs(tenantId, () -> ...)` explicitly, one transaction
  per tenant — the billing cron and the reconciliation job both iterate tenants this way.

`RowLevelSecurityIT` (`meterly-db`) proves this with a one-connection HikariCP pool reused across two
tenants back to back. `meterly-validation`'s RLS check proves the same property over a plain JDBC
connection against the live stack (see [`docs/operations.md`](operations.md) for the query pattern).

## Idempotency at every hop

Meterly assumes **at-least-once** delivery everywhere (HTTP client retries, Kafka's own at-least-once
semantics) and makes every hop safe under redelivery, rather than trying to make delivery exactly-once:

| Hop | Idempotency key | Mechanism |
|---|---|---|
| Ingestion HTTP | `(tenant_id, idempotency_key)` | Redis fast-path (24h TTL): same key+payload → `200 duplicate`; same key, different payload → `409`. **Not** the correctness guarantee — a cache miss or Redis outage still can't cause double-counting. |
| Kafka → ledger | `(tenant_id, idempotency_key)`, a DB unique constraint | `INSERT ... ON CONFLICT DO NOTHING`. This is the real guarantee: Redis being unavailable, evicted, or simply never consulted (a raw Kafka redelivery, bypassing ingestion entirely) still can't create a second ledger row. |
| Usage thresholds | `(tenant_id, resource_id, period, threshold)` | `INSERT INTO usage_threshold ... ON CONFLICT DO NOTHING`, in the same transaction as the ledger insert — a threshold event is only ever queued to the outbox once. |
| Billing | `(tenant_id, period_start)` | `INSERT INTO invoice ... ON CONFLICT DO NOTHING`; a re-run returns the existing invoice (`200`, not `201`). |
| Notifications | `(tenant_id, event_id, recipient)` | Row inserted with `ON CONFLICT DO NOTHING` **before** the email is sent, so a redelivered `notification-events` record can't double-send even if the first send's ack was lost. |

`meterly-validation`'s idempotent-metering check exercises the ledger's guarantee directly: 950 unique
events, 50 client-side duplicate retries, and 50 *exact* Kafka redeliveries (bypassing ingestion,
produced straight to `usage-events`) must all settle on exactly 950 rows.

## Redis: cache, live-update fan-out, and reconciliation — never the source of truth

Redis holds three logically separate things, none of which PostgreSQL depends on:

1. **Rate limiting** — a Lua-scripted token bucket per tenant (`meterly:ratelimit:ingest:{tenant}`),
   atomic read-modify-write, fails **open** (with a metric) if Redis is unreachable — a Redis outage
   degrades to "no rate limiting", not "ingestion is down".
2. **The usage counter projection** — `meterly:usage:{tenant}:{resource}:{yyyy-MM}`, written by a
   version-guarded Lua script keyed on the ledger row id that produced it (`ledger_version`), so a
   late or redelivered projection can never move a counter *backwards* even under out-of-order writes
   from multiple metering-service instances.
3. **Pub/sub fan-out** — `meterly:usage-updates`, so every instance's STOMP subscribers get every
   update regardless of which instance's Kafka partition produced it.

If Redis loses data (eviction, restart, `noeviction` maxmemory policy notwithstanding), `GET
/v1/usage/current` falls back to `usage_counter_state`, then to a ledger `SUM` if even that's missing,
and **re-seeds** Redis through the same version-guarded write — a cache miss self-heals. The nightly
(and on-demand, `POST /internal/v1/reconciliation/run`) reconciliation job independently re-sums the
ledger per tenant/resource and corrects both `usage_counter_state` and Redis on any drift, incrementing
`meterly_reconciliation_drift_total` — drift is expected to be rare (only a crash between ledger commit
and Redis write should ever cause it) and is treated as a metric to watch, not silently ignored.

## Outboxes: transactional writes, at-least-once delivery

billing-service and metering-service each write events they need to publish (`invoice.issued`,
`usage.threshold_crossed`) into an `outbox_event` table **in the same transaction** as the business
write that caused them — so an event is queued if and only if the invoice/threshold row it describes
actually committed; there is no window where one exists without the other. A separate scheduled relay
(ShedLock-guarded, so only one instance runs it cluster-wide) polls unpublished rows
(`FOR UPDATE SKIP LOCKED`, bounded batch), publishes to Kafka, and marks them published — each service
relays only the event types it owns, so two relays never race on the same row. Delivery is
at-least-once; consumers (notification-service) dedupe by `event_id`.

## Redpanda as the Kafka substitute

Local/dev Kafka is [Redpanda](https://redpanda.com): a single Kafka-API-compatible binary with no
ZooKeeper/KRaft cluster to operate, which is the right tradeoff for a project at this scale. It is
**not** silently pretended to be Kafka: `compose.yml` names the service `redpanda`, and every service
config exposes a plain `KAFKA_BOOTSTRAP_SERVERS` so swapping in real Kafka (or a managed offering) in
production is a configuration change, not a code change. Topic bootstrap
(`deploy/redpanda/create-topics.sh`) uses `rpk`, Redpanda's own CLI, run idempotently on every
`docker compose up`.

## Deferred scope

These are deliberate, out-of-scope decisions, named explicitly so they read as decisions rather than
gaps discovered later:

- **Multi-currency and tax** — every plan/invoice is a single `currency` field (`USD` in the seed
  data); no FX conversion, no tax calculation or remittance.
- **Dunning** — a failed/overdue invoice has no retry-collection workflow; `invoice.status` has no
  "past due" or "failed" state machine.
- **Proration** — a mid-period plan change takes effect at the **start of the next period**
  (`PUT /v1/subscription`); there is no partial-period charge or credit.
- **Payment collection** — Meterly generates invoices; it does not charge a card, integrate Stripe/a
  payment processor, or track payment status. This is explicit in the PDD ("Meterly generates
  invoices, it does not collect payment").
- **Keycloak / external OIDC** — auth is tenant-service's own JWT issuer (RS256, JWKS). Federating to
  Keycloak or another IdP was a PDD Phase 3 stretch item; nothing here precludes adding an OAuth2
  resource-server config later, but it isn't wired up.
