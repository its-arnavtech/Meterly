# Why Java for Meterly

Meterly's backend, tooling, load generator and exit-criteria harness are written in **Java 21 (LTS) with
Spring Boot 3**. The only non-Java code is the Angular SPA (browser UI, specified by the PDD) and
infrastructure configuration. This document explains why Java is the right tool for *this* problem —
each point maps a Meterly design requirement to the Java feature that satisfies it, with a pointer to
where it lives in the code.

## 1. Money must never be approximate → `BigDecimal` + static typing

PDD principle #1: *"All monetary values use BigDecimal with an explicit rounding rule; there is no float
or double anywhere in the billing domain."*

- `java.math.BigDecimal` is exact decimal arithmetic in the standard library, with explicit
  `RoundingMode.HALF_EVEN` (banker's rounding). No third-party decimal library, no configuration.
- Static typing makes the rule *enforceable*, not just a convention: billing's domain types
  (`PlanPricing`, `Tier`, `LineItem`, `InvoiceDraft`) are records whose money components are `BigDecimal`. You cannot
  pass a `double` where a `BigDecimal` is expected — the compiler rejects it.
- An **ArchUnit** test in billing-service fails the build if any class in the billing domain declares a
  `float`/`double` field, parameter or return type. The rule is checked on every CI run.
- On the wire, money is serialized as a JSON *string* (`"274.00"`), configured once in
  `meterly-common` (`MeterlyCoreAutoConfiguration`), so JavaScript clients never parse it into a float.

## 2. Correct time handling → `java.time`

Billing periods are calendar months in UTC. `java.time.YearMonth`, `LocalDate`, `Instant` and
`ZoneOffset.UTC` model this directly (`com.meterly.common.billing.BillingPeriod`, a record). A `Clock`
bean is injected everywhere "now" matters, so period-close logic is unit-tested at exact boundaries
(23:59:59.999 on the last day vs. 00:00 on the 1st) without sleeping or mocking statics.

## 3. Immutable, value-based data → records

Every message and value object is a `record`: `UsageEventMessage`, `UsageUpdate`, `BillingPeriod`,
`CurrentUser`, the notification events, pricing results, API DTOs, even `@ConfigurationProperties`
(`MeterlyProperties`).

- **Value equality matters for idempotency**: a redelivered Kafka record deserializes to an *equal*
  `UsageEventMessage`; tests assert exactly that.
- Compact constructors validate invariants at construction time (a `UsageEventMessage` with
  `quantity <= 0` or a `BillingPeriod` not starting on the 1st cannot exist).
- Immutability makes objects safe to share across threads — important with virtual threads and
  concurrent Kafka consumers.

## 4. Closed sets of outcomes → sealed interfaces + exhaustive pattern matching

- `NotificationEvent` is a **sealed interface** (`InvoiceIssued | UsageThresholdCrossed`).
  notification-service dispatches with a `switch` pattern match and **no `default` branch**: adding a
  new event type is a compile error in every consumer until it is handled, instead of a silently dropped
  message in production.
- Service-level results are modelled the same way (e.g. an ingestion outcome of
  `Accepted | Duplicate | Rejected`, a billing run of `Created | AlreadyInvoiced`), which turns "did we
  handle every case?" into something the compiler checks.
- `TenantContextAspect.resolveTenant` uses a type-pattern `switch` (with `case null`) over the
  authentication types (JWT vs. API key).

## 5. I/O-bound concurrency without reactive complexity → virtual threads (Project Loom)

All services run with `spring.threads.virtual.enabled=true`. Request handling in ingestion-service is
dominated by waiting — on Redis (rate limit, idempotency), on tenant-service (API-key verification) and
on the Kafka broker ack. With virtual threads each request keeps simple, blocking, debuggable code while
the JVM parks the virtual thread instead of an OS thread, so throughput scales like an async stack
without `Mono`/`Flux` plumbing. The load generator in `meterly-validation` fires thousands of concurrent
requests with `Executors.newVirtualThreadPerTaskExecutor()` — one virtual thread per simulated client.

`TenantContext` is a `ThreadLocal` with strictly scoped `runAs/callAs` binding (restored in `finally`),
which is safe on virtual threads because each virtual thread has its own thread-locals. (Java 25's final
`ScopedValue` is the natural future replacement.)

## 6. Transactions and RLS → JDBC + Spring's transaction abstraction

Tenant isolation depends on issuing `set_config('app.tenant_id', ?, true)` (i.e. `SET LOCAL`) **inside
every transaction, on the exact pooled connection that runs the queries**. Java's JDBC model plus
Spring's `DataSourceTransactionManager` expose precisely that hook: `TenantAwareTransactionManager`
overrides `prepareTransactionalConnection(...)`, which runs right after `BEGIN` on the transaction's
connection. The PDD's `@Around` aspect (`TenantContextAspect`) binds the tenant from the validated JWT
for each request. `RowLevelSecurityIT` proves isolation holds with a one-connection HikariCP pool
reused across two tenants.

## 7. The ecosystem fits the architecture exactly

| Need (PDD) | Java ecosystem answer |
|---|---|
| Kafka consumer groups, at-least-once, DLT, retries/backoff | Apache Kafka's own client is a JVM library; Spring for Apache Kafka adds `DefaultErrorHandler`, `DeadLetterPublishingRecoverer` |
| JWT issuance + JWKS + resource servers | Spring Security OAuth2 Resource Server / Nimbus JOSE |
| STOMP over WebSocket with per-destination authorization | Spring WebSocket + Spring Security Messaging |
| Scheduled jobs that run once across replicas | `@Scheduled` + ShedLock (Postgres lock table) |
| Schema migrations | Flyway (Java API used by the migrator *and* by tests) |
| Real Postgres/Redpanda/Redis in tests, not mocks | Testcontainers (Java-first) |
| Metrics for Prometheus, structured JSON logs | Micrometer; Spring Boot structured logging (ECS) |
| Architecture rules as tests | ArchUnit |

## 8. Operational maturity

Long-running, multi-threaded services with steady memory behaviour, excellent observability (JFR, JMX,
Micrometer), mature GC, and predictable deployment as a single OCI image per service built from a layered
Spring Boot jar on a JRE base image.

## 9. Built-in HTTP & WebSocket clients → a dependency-free validation harness

The exit-criteria harness (`meterly-validation`) uses only the JDK's `java.net.http.HttpClient` and
`java.net.http.WebSocket` (speaking STOMP frames directly) to measure end-to-end dashboard latency, plus
JDBC for the RLS check and the Kafka client to simulate broker redelivery. No Python or shell scripting.

---

### Java 21 features used — quick index

| Feature | Where |
|---|---|
| Records (incl. compact constructors, config properties) | `meterly-common` events, `BillingPeriod`, `MeterlyProperties`, all DTOs |
| Sealed interfaces | `NotificationEvent`, service outcome types |
| Pattern matching for `switch` (type patterns, `case null`) | `TenantContextAspect`, notification dispatch |
| Virtual threads | `spring.threads.virtual.enabled`, load generator |
| Sequenced collections (`getFirst()`/`getLast()`) | pricing tiers |
| Text blocks | SQL in repositories and tests |
| `java.time` | billing periods, clocks |
| `java.net.http` HttpClient / WebSocket | validation harness |
| `HexFormat`, `SecureRandom`, `MessageDigest` | API-key hashing (`Hashing`) |
