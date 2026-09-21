# Engineering Guide (read before writing code)

## Build & run
- JDK 21+ (CI uses Temurin 21; local machines may run a newer JDK — `--release 21` is enforced).
- Maven wrapper: `./mvnw` (bash) or `.\mvnw.cmd` (PowerShell). Build one module with its deps:
  `./mvnw -B -ntp -pl tenant-service -am verify`. **Do not `install`** snapshot artifacts from a feature branch.
- Unit tests: `*Test.java` (surefire). Integration tests: `*IT.java` (failsafe, Testcontainers; Docker must be running).
- Integration tests use `meterly-test-support`: `MeterlyPostgres.registerDataSource(registry)`,
  `MeterlyRedis.registerProperties(registry)`, `MeterlyRedpanda.registerProperties(registry)`,
  `TestJwt.bearer(tenantId, userId, Role.TENANT_ADMIN)` and `@Import(TestJwtConfiguration.class)`.
  Containers are JVM-wide singletons; call `MeterlyPostgres.truncateTenantData()` in `@BeforeEach` when isolation matters.

## Code conventions
- Java 21 idioms where they add clarity: records for DTOs/value objects/config, sealed interfaces for closed
  outcome sets, pattern-matching `switch` without `default` over sealed types, text blocks for SQL,
  `getFirst()/getLast()`. See `docs/why-java.md` — keep it true.
- **No Lombok.** No field injection — constructor injection only.
- Data access: `JdbcClient` with explicit SQL (text blocks). No JPA. All tenant-scoped SQL inside
  `@Transactional` (request path) or `TenantContext.runAs(tenant, () -> transactionTemplate.execute(...))`
  (consumers/schedulers). One tenant per transaction.
- Never add `WHERE tenant_id = ?` as the *only* protection — RLS is the guarantee. Adding the filter as well
  is fine for index use and clarity.
- Money: `BigDecimal` only; rounding `HALF_EVEN` at 2 dp exactly once per line item. No `float`/`double` in billing.
- Errors: throw `ApiException` with a stable `code` from the contract; never return stack traces.
- Validation: Jakarta Bean Validation on request records (`@Valid @RequestBody`).
- Security: every service has its own `SecurityFilterChain`: stateless, CSRF disabled (bearer tokens; the only cookie
  endpoint `/v1/auth/refresh` is `SameSite=Strict`), `cors(withDefaults())`, `oauth2ResourceServer(jwt(...))` with the
  `MeterlyJwtAuthenticationConverter` bean, `ProblemDetailsSecurityHandlers` for 401/403, `/actuator/health/**`,
  `/actuator/info`, `/actuator/prometheus`, `/v3/api-docs/**` permitted; `/internal/**` via `InternalTokenFilter` + `hasRole("INTERNAL")`.
- Controllers use `@PreAuthorize("hasRole('TENANT_ADMIN')")` (enable `@EnableMethodSecurity`) or path rules.
- Logging: SLF4J, parameterized; never log secrets, passwords, raw API keys, tokens, or full request bodies.
- Metrics: Micrometer counters/timers named `meterly.<service>.<thing>` (Prometheus renders `meterly_<service>_<thing>_total`).
- Kafka: `String` key (tenant_id) + `String` JSON value serialized with the injected Spring `ObjectMapper`.
  Producers: `acks=all`, `enable.idempotence=true`. Consumers: manual/`RECORD` ack after the DB commit,
  `DefaultErrorHandler` with backoff + `DeadLetterPublishingRecoverer`; register `CorrelationRecordInterceptor`.
- Config: `@ConfigurationProperties` records under `meterly.<service>.*`, every value overridable by env var,
  safe local defaults, no secrets committed except obvious dev placeholders.

## Git
- Work only on your feature branch. Commit in logical steps with clear messages. Attribute coauthors only
  when they actually contributed; do not automatically attribute new work to a previous agent.
- Only touch files inside your module unless your brief says otherwise. Shared files (`meterly-common`, root `pom.xml`,
  `docs/api-contract.md`) are owned by the integrator: if you genuinely need a change there, make the smallest
  additive change and call it out explicitly in your final report.
- New SQL migrations: `meterly-db/src/main/resources/db/migration`, only within your reserved version range
  (see `docs/api-contract.md` §8).
