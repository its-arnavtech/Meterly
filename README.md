# Meterly

Meterly is a multi-tenant SaaS usage-metering and tiered-billing platform. Phase 0 proves four risky properties end to end: idempotent event processing, PostgreSQL row-level tenant isolation, live dashboard delivery, and exact invoice arithmetic.

## Repository layout

- `backend/ingestion-service`: usage-event HTTP API and Kafka producer
- `backend/metering-billing-service`: idempotent metering, Redis counters, STOMP updates, and billing
- `frontend`: Angular 18 SPA
- `Docs`: read-only POC/PDD source documents and generated Markdown extracts
- `docker-compose.yml`: PostgreSQL 16, Redis, Redpanda, and both backend services

## Local verification

```text
mvn verify
cd frontend && npm ci && npm run test:ci && npm run build
docker compose config --quiet
docker compose up --build --wait
```

GitHub Actions repeats the backend, frontend, Compose, dependency-review, and CodeQL gates for every feature PR to `main`.
