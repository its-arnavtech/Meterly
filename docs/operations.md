# Operations

## Environment variables

All values are overridable per Spring Boot's normal precedence (env var > system property > profile
YAML > default YAML), regardless of whether a service's `application.yml` shows a `${VAR:default}`
placeholder or a literal — see `docs/engineering-guide.md`. Secrets are marked **secret**; never log
or commit them (`scripts/bootstrap.ps1` generates local-only values into gitignored `.env`/`secrets/`).

### Common to every backend service

| Variable | Default (dev) | Meaning |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` (compose) / `prod` (image) | Activates `ProductionConfigurationGuard` under `prod` — refuses to start with dev-only defaults (e.g. an empty `INTERNAL_TOKEN`) |
| `SERVER_PORT` | per service (8081–8085) | HTTP listen port |
| `DB_URL` | `jdbc:postgresql://postgres:5432/meterly` | JDBC URL (not read by ingestion-service, which has no datasource) |
| `DB_USERNAME` / `DB_PASSWORD` **(secret)** | `meterly_app` / — | The RLS-enforced application role; never the owner |
| `DB_POOL_SIZE` | `10` | HikariCP max pool size |
| `KAFKA_BOOTSTRAP_SERVERS` | `redpanda:9092` | Kafka API endpoint |
| `REDIS_HOST` / `REDIS_PORT` | `redis` / `6379` | — |
| `JWT_JWKS_URI` | `http://tenant-service:8081/.well-known/jwks.json` | Where resource servers fetch tenant-service's public signing key |
| `JWT_ISSUER` / `JWT_AUDIENCE` | `meterly` / `meterly-api` | Must match tenant-service's issued tokens |
| `INTERNAL_TOKEN` **(secret)** | — | Shared secret for `/internal/**` (`X-Internal-Token`); `ProductionConfigurationGuard` refuses the built-in dev placeholder under `prod` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080,http://localhost:4200` | Comma-separated allow-list |
| `TENANT_SERVICE_URL` | `http://tenant-service:8081` | Used by ingestion-service to verify API keys |
| `MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS` | `true` (compose) | Emits Prometheus histogram buckets so Grafana can compute p95/p99 (see `deploy/observability`) |

### tenant-service only

| Variable | Meaning |
|---|---|
| `JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH` | PEM file paths (compose mounts `./secrets` read-only at `/run/keys`) |
| `JWT_KEY_ID` | The `kid` published in JWKS and stamped on issued tokens |
| `REFRESH_COOKIE_SECURE` | Set `true` outside plain-HTTP dev so the refresh cookie requires TLS |
| `FORWARD_HEADERS_STRATEGY` | `none` locally; set `framework` behind a trusted reverse proxy that sets `X-Forwarded-*` |

### ingestion-service only

| Variable | Default | Meaning |
|---|---|---|
| `METERLY_INGESTION_MAX_EVENT_AGE` | `PT1H` | How far in the past `occurred_at` may be before `422 EVENT_TOO_OLD` |
| `METERLY_INGESTION_MAX_CLOCK_SKEW` | `PT5M` | How far in the future before `422 EVENT_IN_FUTURE` |
| `METERLY_INGESTION_RATE_LIMIT_CAPACITY` | `200` | Per-tenant token-bucket burst capacity |
| `METERLY_INGESTION_RATE_LIMIT_TOKENS_PER_SECOND` | `100` | Refill rate |

### billing-service only

| Variable | Default | Meaning |
|---|---|---|
| `METERLY_BILLING_CLOSE_CRON` | `0 0 2 1 * *` (UTC) | When the previous period is billed for every active tenant |
| `METERLY_BILLING_OUTBOX_DELAY` | `2000` (ms) | Outbox relay poll interval |

### notification-service only

| Variable | Default | Meaning |
|---|---|---|
| `EMAIL_PROVIDER` | `logging` | `logging` (stub, logs instead of sending) or `smtp` |
| `EMAIL_FROM` | `billing@meterly.local` | From address |
| `SMTP_HOST` / `SMTP_PORT` | `localhost` / `1025` | Compose points this at `mailpit` |

### Migrator

`DB_URL`, `DB_OWNER_USERNAME`/`DB_OWNER_PASSWORD` **(secret)** (the schema-owning role Flyway runs
as), `DB_APP_ROLE` (grantee for the migrations' `GRANT` statements), `MIGRATION_LOCATIONS` — compose
sets this to `classpath:db/migration,classpath:db/demo`. **For a real deployment, override it to just
`classpath:db/migration`** so no demo tenants/logins/usage are ever created.

## Secrets & JWT key rotation

Local secrets are generated once by `scripts/bootstrap.ps1` into `.env` and `secrets/jwt-{private,public}.pem`
(both gitignored; the script refuses to overwrite an existing `.env`). For anything beyond a laptop:
replace `.env`/`secrets/` with your platform's secret manager and the Kubernetes `Secret` example at
`deploy/k8s/base/secret.example.yaml` documents the exact keys every Deployment expects.

**Rotating the JWT signing key** (tenant-service is the sole issuer):
1. Generate a new RSA key pair with a new `kid` (e.g. `openssl genrsa -out jwt-private-2.pem 3072`).
2. Publish *both* keys in JWKS during the rollover window — tenant-service's JWKS endpoint should list
   every key whose `kid` might still be verifying an unexpired (≤15 min TTL) token, so the simplest safe
   sequence is: deploy the new key pair as `JWT_PRIVATE_KEY_PATH`/`JWT_KEY_ID` (tenant-service starts
   signing with it immediately), while any resource server that already cached the old JWKS response
   still validates outstanding tokens against it until its cache TTL expires.
3. After one full token TTL (15 min) plus your resource servers' JWKS cache TTL, the old key can be
   removed from the JWKS set entirely.

**Rotating `INTERNAL_TOKEN` or `DB_APP_PASSWORD`**: update the secret store, then roll every service
that reads it (a plain restart — nothing caches these beyond process lifetime).

## Scaling: partitions vs. consumer concurrency

`usage-events` has 12 partitions, `notification-events` has 6 (`deploy/redpanda/create-topics.sh`),
keyed by `tenant_id` — this caps parallelism *per consumer group* at the partition count and preserves
per-tenant ordering (all of one tenant's events land on the same partition). To scale metering-service:

- **More consumer instances** (up to the partition count — the Kubernetes HPA in
  `deploy/k8s/base/hpa.yaml` caps `metering-service` at `maxReplicas: 12` for exactly this reason).
  Beyond 12 replicas, extra instances sit idle in the consumer group.
- **More partitions** if 12 replicas isn't enough headroom — requires `rpk topic add-partitions
  usage-events --num N` (partitions can only grow, never shrink) and rebalances existing consumers;
  do this during low traffic.
- ingestion-service and the SPA's WebSocket layer scale horizontally with no partitioning concerns
  (ingestion is stateless per request; STOMP fan-out uses Redis pub/sub across instances, see
  `docs/architecture.md`).

## Dead-letter topics: inspection and replay

Non-retryable failures (bad JSON, an unknown/foreign resource id) go to `usage-events.DLT` /
`notification-events.DLT` (same partition count as their source topic — Spring's
`DeadLetterPublishingRecoverer` preserves partition number by default) with error details in Kafka
record headers. Using `rpk` (bundled in the `redpanda`/`topics` container image; run via
`docker compose exec redpanda rpk ...`, or against the host-published port with
`rpk -X brokers=localhost:19092 ...`):

```bash
# List DLT topics and their message counts
rpk topic list usage-events.DLT notification-events.DLT

# Inspect the last few dead-lettered records, including error headers
rpk topic consume usage-events.DLT -n 5 --print-headers

# Check consumer group lag (is metering-service keeping up?)
rpk group describe metering-service
```

There is no automated DLT replay tool; a dead-lettered record failed because it could never succeed
(the resource it references doesn't exist, or the payload doesn't deserialize) — the operator's job is
to read the error headers, fix the underlying data problem (or confirm it's genuinely bad input to
discard), and, only if the record should actually be reprocessed, republish its `value` to the source
topic with `rpk topic produce usage-events --key <tenant_id>` using the original bytes.

## Reconciliation

metering-service runs a nightly job (03:00 UTC, ShedLock `metering-reconciliation`) and exposes it
on-demand at `POST /internal/v1/reconciliation/run`: for every active tenant, one transaction per
tenant re-sums the ledger for the open period per resource and corrects `usage_counter_state` and the
Redis projection on any drift, logging `WARN` and incrementing `meterly_reconciliation_drift_total`
(see the **Meterly - Usage & Billing Pipeline** Grafana dashboard). Drift should be rare — see
`docs/architecture.md`'s Redis section for when it can legitimately happen.

## Backups

PostgreSQL is the only stateful system of record (Redis is a rebuildable cache; Kafka/Redpanda retains
`usage-events` for 7 days and its DLTs for 14 — plenty to replay a consumer, not a backup). For a real
deployment: use your managed Postgres provider's point-in-time recovery, or `pg_dump`/WAL archiving if
self-hosting. The Compose stack's `postgres-data` volume is local-disk only and is **not** a backup
strategy — it exists for developer convenience (state survives `docker compose down` without `-v`).

## Health, metrics, runbook

- **Liveness/readiness**: `GET /actuator/health/{liveness,readiness}` on each service's own port (not
  exposed through the SPA's nginx proxy — `deploy/nginx/default.conf` explicitly 404s `/actuator/**`).
  Kubernetes probes and the Compose healthchecks both use these.
- **Metrics**: `GET /actuator/prometheus`. Every custom counter follows `meterly_<service>_<thing>_total`
  (Micrometer `meterly.<service>.<thing>` renders with underscores and a `_total` suffix for counters).
  See `deploy/observability/prometheus/prometheus.yml` for scrape targets and the two Grafana
  dashboards for what's plotted.
- **Correlation**: every request/response carries `X-Request-Id`; Kafka records carry the same value
  in an `x-request-id` header; every log line includes `request_id` in its MDC (structured ECS JSON
  logs by default — `logging.structured.format.console`). When investigating an incident, start from
  the request id in a user-facing error and grep for it across every service's logs.
- **A service won't become ready**: check `/actuator/health/readiness` for the failing indicator
  (typically the datasource or Kafka). Check the migrator Job/container completed successfully first —
  every service's Compose `depends_on` blocks on it, but a stale image or a failed migration on a prior
  run can leave things inconsistent; `docker compose logs migrator`.
- **429s under normal load**: check `meterly_ingestion_rate_limit_fail_open_total` (Redis reachable?)
  and the per-tenant burst — `METERLY_INGESTION_RATE_LIMIT_CAPACITY`/`_TOKENS_PER_SECOND` may need
  raising for a legitimately high-volume tenant.
- **A billing run returns `422 PERIOD_NOT_CLOSED` or `NO_SUBSCRIPTION`**: expected — billing only
  prices a period whose `period_end` has passed, and only for a tenant with a subscription overlapping
  it. `POST /internal/v1/billing/run-all` reports `skipped_no_subscription` counts for exactly this.
