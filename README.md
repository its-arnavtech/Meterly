# Meterly

Meterly is a multi-tenant SaaS usage-metering and tiered-billing platform. The implemented Phase 2 system covers onboarding, tenant-isolated ingestion/metering/billing, live dashboard delivery, exact invoice arithmetic, rate limiting, reconciliation, and defensive RLS tests.

## Repository layout

- `backend/ingestion-service`: usage-event HTTP API and Kafka producer
- `backend/tenant-service`: tenant/user/plan/resource onboarding and API-key issuance
- `backend/metering-service`: idempotent Kafka consumption, Redis counters, and tenant-scoped STOMP updates
- `backend/billing-service`: tiered pricing, billing runs, and tenant-isolated invoices
- `frontend`: Angular 21 SPA
- `Docs`: read-only POC/PDD source documents and generated Markdown extracts
- `docker-compose.yml`: PostgreSQL 16, Redis, Redpanda, four backend services, and the web app

## Local secrets

Compose intentionally has no credential or signing-key fallbacks. Generate an ignored `.env` before the first start:

```powershell
.\scripts\initialize-env.ps1
```

The script creates cryptographically random local-only values without printing them. Do not copy `.env` into an image, commit it, or reuse these values outside local development. Use `-Force` only when intentionally rotating the local stack credentials.

## Local verification

```text
mvn verify
cd frontend && npm ci && npm run test:ci && npm run build
docker compose config --quiet
docker compose up --build --wait
```

GitHub Actions repeats the secret scan, backend, frontend, Compose, Testcontainers, live-smoke, production dependency-review, and CodeQL gates for every feature PR to `main`.
