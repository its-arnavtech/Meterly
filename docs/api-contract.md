# Meterly API & Integration Contract

This is the binding contract between services and the SPA. Every agent/branch implements against it.
If you must deviate, document the deviation in your branch's final report — do not silently change shapes.

## 0. Conventions (all services)

| Topic | Rule |
|---|---|
| Base path | `/v1/...` for public APIs, `/internal/v1/...` for service-to-service |
| JSON naming | **snake_case** everywhere (configured globally by `meterly-common`) |
| Money | JSON **strings** in plain decimal notation: `"274.00"`, unit prices `"0.002000"`. Never JSON numbers. Java type is always `BigDecimal` |
| Timestamps | ISO-8601 UTC instants: `"2026-09-21T17:04:05Z"` |
| Dates | ISO dates: `"2026-08-01"`. Billing periods are calendar months in UTC, `[period_start, period_end)` |
| IDs | UUID strings |
| Lists | Wrapped: `{ "items": [ ... ] }` |
| Errors | RFC 7807 `application/problem+json`: `{ "type", "title", "status", "detail", "instance", "code", "request_id", "errors"?: [{ "field", "message" }] }`. `code` is a stable UPPER_SNAKE identifier. Throw `com.meterly.common.web.ApiException` |
| Correlation | `X-Request-Id` request/response header; Kafka header `x-request-id`; MDC key `request_id` |
| Auth (users) | `Authorization: Bearer <JWT>` (RS256, issued by tenant-service, validated via JWKS) |
| Auth (machines) | `X-API-Key: mk_live_...` (ingestion only) |
| Auth (internal) | `X-Internal-Token: <shared secret>` on `/internal/**` (see `InternalTokenFilter`) |
| Roles | `TENANT_ADMIN` (everything below), `TENANT_USER` (usage dashboard only: `/v1/me`, `/v1/resources` GET, `/v1/usage/**`, WS) |
| Health/metrics | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` (not exposed through the SPA proxy) |
| OpenAPI | `/v3/api-docs` per service (springdoc) |

### JWT claims
```json
{ "iss": "meterly", "aud": ["meterly-api"], "sub": "<user uuid>", "iat": 0, "exp": 0,
  "tenant_id": "<tenant uuid>", "role": "TENANT_ADMIN", "email": "admin@acme.test" }
```
Access-token TTL 15 minutes. Header carries `kid`; JWKS at `GET http://tenant-service:8081/.well-known/jwks.json`.

### Ports (local & compose service names)
| Service | Port |
|---|---|
| tenant-service | 8081 |
| ingestion-service | 8082 |
| metering-service | 8083 |
| billing-service | 8084 |
| notification-service | 8085 |
| meterly-web (nginx: SPA + reverse proxy) | 8080 (container), dev server 4200 |
| PostgreSQL 5432, Redis 6379, Redpanda 19092 (host) / redpanda:9092 (in-network), Redpanda Console 8090, Mailpit UI 8025 |

### Reverse-proxy routing (nginx in compose, `proxy.conf.json` for `ng serve`)
| Path | Upstream |
|---|---|
| `/v1/auth/**`, `/v1/tenants`, `/v1/me`, `/v1/tenant`, `/v1/plans`, `/v1/subscription`, `/v1/resources/**`, `/v1/users/**`, `/v1/api-keys/**` | tenant-service |
| `/v1/usage-events` (+ `/v1/usage-events/batch`) | ingestion-service |
| `/v1/usage/**`, `/ws/usage` (WebSocket upgrade) | metering-service |
| `/v1/billing/**`, `/v1/invoices/**` | billing-service |
| `/v1/notifications/**` | notification-service |

---

## 1. tenant-service (8081)

### POST /v1/tenants — public signup (tenant onboarding)
Request:
```json
{ "tenant_name": "Acme Corp", "admin_email": "admin@acme.test", "admin_password": "Meterly!2026", "plan_code": "STARTER" }
```
- `tenant_name` 2..100 chars; `admin_email` valid email ≤254; `admin_password` 10..72 bytes; `plan_code` optional (default `STARTER`, must be an active plan).
- Creates tenant, TENANT_ADMIN user (bcrypt), subscription starting **today** (base fee is not prorated), and a default metered resource `{"name":"API calls","unit_label":"call"}`.
- Throttled per client IP (Redis token bucket).

`201 Created`:
```json
{ "tenant": { "id": "…", "name": "Acme Corp", "status": "ACTIVE", "created_at": "…" },
  "admin":  { "id": "…", "email": "admin@acme.test", "role": "TENANT_ADMIN", "status": "ACTIVE", "created_at": "…" },
  "subscription": { …Subscription… },
  "resources": [ { …Resource… } ] }
```
Errors: `409 EMAIL_TAKEN`, `400 VALIDATION_FAILED`, `422 UNKNOWN_PLAN`, `429 TOO_MANY_REQUESTS`.

### POST /v1/auth/login — public
Request `{ "email": "…", "password": "…" }` → `200`:
```json
{ "access_token": "<jwt>", "token_type": "Bearer", "expires_in": 900,
  "user": { "id": "…", "email": "…", "role": "TENANT_ADMIN", "tenant_id": "…", "tenant_name": "Acme Corp" } }
```
Also sets the refresh cookie: `Set-Cookie: meterly_refresh=<opaque>; HttpOnly; SameSite=Strict; Path=/v1/auth; Max-Age=604800` (+ `Secure` when `meterly.auth.refresh-cookie-secure=true`).
Errors: `401 INVALID_CREDENTIALS` (same response for unknown email, wrong password, disabled user, suspended tenant — no enumeration), `429 TOO_MANY_ATTEMPTS` (throttled per IP and per email; includes `Retry-After`).

### POST /v1/auth/refresh — cookie auth
Rotates the refresh token (old one revoked, new cookie set). Reuse of an already-rotated token revokes the whole token family. `200` same body as login. `401 INVALID_REFRESH_TOKEN`.

### POST /v1/auth/logout
Revokes the presented refresh token (if any) and clears the cookie. `204`.

### GET /v1/me — any role
```json
{ "user": { "id", "email", "role", "status", "created_at" }, "tenant": { "id", "name", "status", "created_at" } }
```

### GET /v1/tenant — any role → Tenant. PATCH /v1/tenant — admin, `{ "name": "…" }` → Tenant.

### GET /v1/plans — public
```json
{ "items": [ { "id": "…", "code": "STARTER", "name": "Starter", "base_price": "19.00", "included_units": 10000, "currency": "USD",
  "tiers": [ { "from_unit": 10000, "to_unit": 100000, "unit_price": "0.002000" }, { "from_unit": 100000, "to_unit": null, "unit_price": "0.001500" } ] } ] }
```
Tier semantics: a tier prices cumulative units in `(from_unit, to_unit]`; `units_in_tier = max(0, min(total, to_unit ?? ∞) − from_unit)`. Included units are free. Plan allowance/tiers apply **per metered resource**; the base fee is charged once per period.

### GET /v1/subscription — admin
```json
{ "current":   { "id", "plan": { …Plan… }, "start_date": "2026-09-01", "end_date": null, "status": "ACTIVE" },
  "scheduled": null }
```
### PUT /v1/subscription — admin, `{ "plan_code": "GROWTH" }`
Plan changes take effect at the **start of the next billing period** (no mid-period proration): the current subscription gets `end_date = next period start`; a new subscription starts then (`scheduled` in the response). Choosing the current plan again cancels a pending change. `422 UNKNOWN_PLAN`.

### Resources
- `GET /v1/resources` (any role) → `{ "items": [ { "id", "name", "unit_label", "status": "ACTIVE|ARCHIVED", "created_at" } ] }`
- `POST /v1/resources` (admin) `{ "name": "Webhooks", "unit_label": "delivery" }` → `201` Resource; `409 RESOURCE_NAME_TAKEN`
- `PATCH /v1/resources/{id}` (admin) `{ "name"?, "unit_label"?, "status"? }` → Resource; `404 RESOURCE_NOT_FOUND`

### Users (admin)
- `GET /v1/users` → `{ "items": [ { "id", "email", "role", "status", "created_at" } ] }`
- `POST /v1/users` `{ "email", "password", "role" }` → `201` User; `409 EMAIL_TAKEN`
- `PATCH /v1/users/{id}` `{ "role"?, "status"?: "ACTIVE|DISABLED" }` → User. `422 LAST_ADMIN` if it would leave no active admin; `422 CANNOT_MODIFY_SELF` for disabling/demoting yourself.

### API keys (admin)
- `GET /v1/api-keys` → `{ "items": [ { "id", "name", "prefix", "created_at", "last_used_at", "revoked_at" } ] }`
- `POST /v1/api-keys` `{ "name": "Production" }` → `201` `{ …ApiKey…, "key": "mk_live_<43 url-safe chars>" }` — the only time the raw key is returned. Stored as SHA-256 hex; `prefix` = first 12 chars.
- `DELETE /v1/api-keys/{id}` → `204` (revoke; sets `revoked_at`).

### GET /.well-known/jwks.json — public. RSA public key(s) with `kid`, `alg: RS256`, `use: sig`.

### Internal (X-Internal-Token)
- `POST /internal/v1/api-keys/verify` `{ "api_key": "mk_live_…" }` → `200` `{ "tenant_id", "api_key_id", "resource_ids": ["…"] }` (active resources only). `401 INVALID_API_KEY` for unknown/revoked keys or suspended tenants. Updates `last_used_at` at most once per minute.
- `GET /internal/v1/tenants/{tenantId}/resources` → `{ "tenant_id", "resource_ids": [...] }` (active only). `404` if tenant unknown/suspended.
- `GET /internal/v1/tenants/{tenantId}/admins` → `{ "items": [ { "email" } ] }` active TENANT_ADMIN users (used by notification-service; it may instead query the DB directly under RLS).

---

## 2. ingestion-service (8082)

### POST /v1/usage-events
Auth: `X-API-Key` **or** user JWT with `TENANT_ADMIN`. The tenant always comes from the credential, never the body.
```json
{ "resource_id": "…", "idempotency_key": "req-7f3a…", "quantity": 1, "occurred_at": "2026-09-21T17:04:05Z" }
```
- `idempotency_key` required, 1..255 chars of `[A-Za-z0-9._:\-]`. `quantity` integer 1..1,000,000,000. `occurred_at` optional (default: now); must be within `[now − 1h, now + 5m]` (configurable `meterly.ingestion.max-event-age`, `max-clock-skew`); outside → `422 EVENT_TOO_OLD` / `422 EVENT_IN_FUTURE`.
- `resource_id` must be an active resource of the tenant → else `422 UNKNOWN_RESOURCE`.
- Per-tenant token-bucket rate limit (Redis, Lua, atomic) → `429 RATE_LIMITED` + `Retry-After` + `X-RateLimit-Limit` / `X-RateLimit-Remaining`. Fails open (with a metric) if Redis is unavailable.
- Idempotency fast path (Redis, 24h): same key + same payload fingerprint already accepted → `200 {"status":"duplicate"}` without republishing; same key + different payload → `409 IDEMPOTENCY_KEY_REUSED`. The record is written only **after** Kafka acknowledges the publish. Correctness does not depend on Redis: metering's unique constraint is the real guarantee.
- Publishes `UsageEventMessage` to topic `usage-events`, key = tenant_id, waits for the broker ack (`acks=all`, idempotent producer). Broker unavailable → `503 INGESTION_UNAVAILABLE`.

`202 Accepted`:
```json
{ "status": "accepted", "idempotency_key": "req-7f3a…", "received_at": "2026-09-21T17:04:05.123Z" }
```

### POST /v1/usage-events/batch
`{ "events": [ …up to 100 of the above… ] }` → `200 { "results": [ { "idempotency_key", "status": "accepted|duplicate|rejected", "code"?: "…", "detail"?: "…" } ] }`. Rate limiter consumes one token per event; if the batch would exceed the bucket the whole batch gets `429`.

---

## 3. metering-service (8083)

### Kafka consumer
Consumer group `metering-service` on `usage-events`. For each record, inside one transaction bound to the record's tenant:
1. take a transaction-scoped advisory lock for (tenant, resource, period) so projection versions follow commit order;
2. `INSERT INTO usage_event … ON CONFLICT (tenant_id, idempotency_key) DO NOTHING RETURNING id`;
3. only if a row was inserted: upsert the transactional projection `usage_counter_state` (`total_units += quantity`, `ledger_version = usage_event.id`) and evaluate usage thresholds (below) — all in the same transaction as the ledger row.

After COMMIT, project the snapshot into Redis (`RedisKeys.usageCounter`, plus a `:version` key, TTL 70d) with a Lua script that only overwrites when the new `ledger_version` is higher (a late or replayed projection can never move a counter backwards), and publish a `UsageUpdate` on Redis channel `meterly:usage-updates`. A duplicate (conflict) never changes any total; it may re-project the existing snapshot (idempotent), which also repairs a projection lost to a crash between commit and Redis write. Every instance subscribes to the channel and forwards to its local STOMP subscribers, so a browser connected to any instance gets every update.
Non-retryable failures (deserialization, validation, FK violation = unknown/foreign resource) → `usage-events.DLT`. Transient failures → exponential backoff retries.

### GET /v1/usage/current — any role
```json
{ "period_start": "2026-09-01", "period_end": "2026-10-01",
  "plan": { "code": "STARTER", "name": "Starter", "included_units": 10000 },
  "items": [ { "resource_id", "resource_name": "API calls", "unit_label": "call", "total_units": 4200,
               "included_units": 10000, "percent_of_included": 42.0 } ] }
```
Reads Redis; on a miss falls back to `usage_counter_state` (then to a ledger `SUM` if no projection row exists) and re-seeds Redis through the same version-guarded script. All active resources are listed (0 when no usage).

### GET /v1/usage/timeseries?resource_id=…&granularity=hour|day&from=…&to=… — any role
Defaults: current period, `day`. Max range 93 days (hour granularity: max 7 days). From the ledger (source of truth).
```json
{ "resource_id": "…", "granularity": "day", "from": "…", "to": "…",
  "points": [ { "bucket_start": "2026-09-01T00:00:00Z", "units": 1200 } ] }
```

### WebSocket (STOMP over native WebSocket) — `ws(s)://<host>/ws/usage`
- CONNECT frame header `Authorization: Bearer <jwt>` (validated; connection refused otherwise).
- SUBSCRIBE destination must be `/topic/tenants/{own tenant_id}/usage` (any other destination → ERROR frame).
- MESSAGE body = `UsageUpdate` JSON:
```json
{ "tenant_id", "resource_id", "period_start": "2026-09-01", "total_units": 4201, "delta": 1,
  "occurred_at": "…", "recorded_at": "…" }
```
- Server heartbeats 10s/10s.

### Usage thresholds
When a resource's open-period total crosses 80% and 100% of the plan's included units, emit `UsageThresholdCrossed` exactly once per (tenant, resource, period, threshold): inside the ingest transaction insert into `usage_threshold` (`ON CONFLICT DO NOTHING`) and, if inserted, write the event to `outbox_event`. metering-service runs its own outbox relay for `event_type = 'usage.threshold_crossed'` (ShedLock `metering-outbox-relay`). (`RedisKeys.thresholdMarker` is no longer used.)

### Reconciliation
Nightly (03:00 UTC, ShedLock `metering-reconciliation`) and via `POST /internal/v1/reconciliation/run`: for each active tenant (one transaction per tenant), re-sum the ledger for the open period per resource under the projection lock, correct `usage_counter_state` and Redis when either drifted, log WARN and increment `meterly.reconciliation.drift`. Response `{ "tenants_checked", "counters_checked", "counters_corrected" }`.

---

## 4. billing-service (8084)

### POST /v1/billing/run — admin
Request `{ "period_start": "2026-08-01" }` (optional; default = previous month). Must be the 1st of a month and the period must be **closed** (`period_end <= now`) → else `422 PERIOD_NOT_CLOSED` / `400 INVALID_PERIOD`. No subscription overlapping the period → `422 NO_SUBSCRIPTION`.
- `201 Created` + `Location` when the invoice was generated now; `200 OK` with the existing invoice when it already existed (idempotent re-run).
- Body = Invoice detail (below).

Algorithm (one transaction, tenant-bound, advisory-locked per tenant+period):
1. Upsert `usage_period_aggregate` from `SUM(quantity)` of the ledger per active-or-used resource (source of truth — never Redis).
2. Subscription overlapping the period → plan → included units + tiers.
3. Price per resource: included units free; each tier `units × unit_price` → amount rounded **once**, `HALF_EVEN` to 2 decimals at the line item. Base fee line once. Total = sum of rounded line amounts.
4. `INSERT … ON CONFLICT (tenant_id, period_start) DO NOTHING`; if nothing inserted, return the existing invoice.
5. Write `InvoiceIssued` to `outbox_event` in the same transaction.

Invoices snapshot `plan_code` and `plan_name` (V30) so a later catalog rename never changes an issued invoice.

### GET /v1/invoices — admin
```json
{ "items": [ { "id", "invoice_number": "INV-202608-000001", "period_start", "period_end", "status": "ISSUED",
               "currency": "USD", "total_amount": "278.69", "issued_at" } ] }
```
Newest period first.

### GET /v1/invoices/{id} — admin
```json
{ "id", "invoice_number", "period_start", "period_end", "status", "currency", "total_amount", "issued_at",
  "plan": { "code": "STARTER", "name": "Starter" },
  "line_items": [
    { "line_number": 1, "description": "Starter plan — base fee", "resource_id": null, "quantity": 1, "unit_price": "19.000000", "amount": "19.00" },
    { "line_number": 2, "description": "API calls — included (1–10,000)", "resource_id": "…", "quantity": 10000, "unit_price": "0.000000", "amount": "0.00" },
    { "line_number": 3, "description": "API calls — 10,001–100,000", "resource_id": "…", "quantity": 90000, "unit_price": "0.002000", "amount": "180.00" }
  ] }
```
`404 INVOICE_NOT_FOUND` (also for other tenants' ids — RLS makes them invisible).

### GET /v1/billing/preview — admin
Estimated invoice for the **open** period from the ledger so far: same shape as invoice detail with `"id": null, "invoice_number": null, "status": "PREVIEW"`.

### Scheduler
Cron `0 0 2 1 * *` UTC (`meterly.billing.close-cron`), ShedLock `billing-period-close`: bills the previous period for every active tenant (`system_active_tenant_ids()`), one transaction per tenant, failures isolated and counted (`meterly_billing_runs_total{outcome}`).
Internal: `POST /internal/v1/billing/run-all` `{ "period_start"? }` → `{ "period_start", "created", "already_invoiced", "skipped_no_subscription", "failed" }`.

### Outbox relay
Every 2s (ShedLock `billing-outbox-relay`): publish unpublished `outbox_event` rows **whose `event_type` is `invoice.issued`** to their topic (key = tenant_id), mark `published_at`. Each service relays only the event types it owns, so relays never race on the same rows. At-least-once; consumers dedupe by `event_id`.

---

## 5. notification-service (8085)

Consumer group `notification-service` on `notification-events` (sealed `NotificationEvent`, dispatched with a pattern-matching switch). For each event: resolve recipients (active TENANT_ADMIN emails of the tenant, under RLS), render subject/body, send via `EmailSender` (`logging` stub by default, `smtp` → Mailpit in compose), insert into `notification` with `ON CONFLICT (tenant_id, event_id, recipient) DO NOTHING` **before** sending so redelivery never double-sends.

### GET /v1/notifications — admin
`{ "items": [ { "id", "type": "invoice.issued", "recipient", "subject", "status": "SENT|FAILED", "created_at" } ] }` newest first, max 100.

---

## 6. Kafka topics

| Topic | Key | Value | Producer → Consumer |
|---|---|---|---|
| `usage-events` (12 partitions) | tenant_id | `UsageEventMessage` JSON | ingestion → metering |
| `usage-events.DLT` | tenant_id | original bytes + error headers | metering |
| `notification-events` (6 partitions) | tenant_id | `NotificationEvent` JSON (`type` discriminator) | billing (outbox), metering → notification |
| `notification-events.DLT` | tenant_id | original + error headers | notification |

Serialize with the Spring `ObjectMapper` to a `String` value (no Spring type headers). Records are self-describing via `@JsonNaming`/`@JsonTypeInfo` in `meterly-common`.

## 7. Redis keys — see `com.meterly.common.redis.RedisKeys` (single source of truth).

## 8. Database
Schema, RLS policies, grants and SECURITY DEFINER functions are in `meterly-db/src/main/resources/db/migration`.
Services connect as `meterly_app` (RLS enforced) and never run DDL. **New migrations go in `meterly-db` with these reserved version ranges** so parallel branches never collide:

| Owner | Versions |
|---|---|
| foundation | V1–V9 |
| tenant-service | V10–V19 |
| metering-service | V20–V29 |
| billing-service | V30–V39 |
| notification-service | V40–V49 |
| platform | V50–V59 |

Tables added after the foundation: `usage_counter_state`, `usage_threshold` (V20); invoice `plan_code`/`plan_name` (V30); notification delivery status (V40); refresh-token/subscription indexes (V10).

Any new table must get `tenant_id`, RLS (`ENABLE ROW LEVEL SECURITY` + `tenant_isolation` policy) and grants to `${appRole}` in the same migration.

All tenant-scoped SQL must run inside a transaction (`@Transactional` / `TransactionTemplate`) with `TenantContext` bound — the `TenantAwareTransactionManager` binds `app.tenant_id` at `BEGIN`. Outside a request (Kafka listeners, schedulers) use `TenantContext.runAs(tenantId, () -> tx.execute(...))`, one transaction per tenant.

## 9. Validation bar (release evidence)

The `meterly-validation` harness must demonstrate, against the full Docker Compose stack, and record the
results plus the environment (CPU, RAM, OS, Docker resources) in a report:

1. **Three organizations** active at once (the two demo tenants plus at least one tenant created through
   `POST /v1/tenants`), with PostgreSQL 16 RLS proven by querying as `meterly_app` without a tenant filter.
2. **Idempotent metering:** 950 unique usage events plus 50 client retries (duplicate idempotency keys) plus
   50 Kafka redeliveries (identical records produced directly to the topic) → exactly 950 ledger rows and the
   matching counter.
3. **Cent-accurate billing**, independently recomputed by the harness from `GET /v1/plans` and the known usage,
   for each organization; a re-run returns the same invoice (idempotent).
4. **Per-tenant rate limits:** a burst beyond the bucket gets `429` + `Retry-After` for that tenant only,
   while another tenant is unaffected; the client recovers after waiting.
5. **Live STOMP latency:** at least 50 trials from ingestion response to STOMP message; report p50/p95/p99/max.
   Pass: p95 < 850 ms, and the POC bar (≥ 18 of 20 within 2 s). Report the distribution; never claim an
   unconditional guarantee.
