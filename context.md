# Meterly implementation handoff

Last updated: 2026-09-21. This file is the entry point for Claude or Codex resuming work.

## Objective and current status

Complete the PDD/POC core application, harden it, and validate the resume claims with real executions. **Production readiness is not yet established.** Do not treat draft results, source code, or passing unit tests as proof of end-to-end behavior or latency.

The starting `main` was `78882ee` (one local foundation commit ahead of origin/main). Claude's seven worktrees were preserved. Most services were skeletons. Notification delivery implementation, frontend scaffolding, and Docker/bootstrap fragments existed in uncommitted worktrees.

## Branches and ownership

- `codex/production-integration`, C:/Meterly: integration, shared security, infrastructure, notification completion, validation, documentation.
- `codex/tenant-platform`, .claude/worktrees/codex-tenant: tenant/auth and V10–V19 migrations.
- `codex/usage-pipeline`, .claude/worktrees/codex-usage: ingestion/metering and V20–V29 migrations.
- `codex/billing-web`, .claude/worktrees/codex-billing-web: billing, Angular, V30–V39 migrations.

Agents commit on their branches; integrator reviews and merges into the integration branch. Open a final PR to main after validation. Do not directly modify or push main. Existing Claude worktrees are recovery sources, not active development directories. Do not delete them.

## Requirements and decisions

- Read `Meterly_PDD.docx`, `Meterly_POC.docx` in full; searchable extractions are in docs/Meterly_PDD.txt and docs/Meterly_POC.txt.
- `docs/api-contract.md` gives precise API behavior and pricing rules. Four core microservices are tenant, ingestion, metering, billing; notification is an optional fifth.
- Resume evidence must exercise three organizations, PostgreSQL 16 RLS, 950 unique usage events under client retries and Kafka redelivery, independently verified cent-accurate billing, tenant rate limits, and measured STOMP updates under 850 ms. Report distributions and environment, not an unconditional latency guarantee.
- Vercel deployment is deferred until implementation and validation finish. Java/Kafka/PostgreSQL/Redis need backend hosting; Vercel can host the frontend.
- Never commit real keys, passwords, access tokens, `.env`, private keys, or raw authenticated request traces. Generate local secrets into ignored files.
- Historical engineering-guide Claude coauthor instruction is stale: do not falsely attribute new work.

## Validation

Baseline Maven verify started; results pending. Docker Engine 29.5.3 and JDK 26 are available; compilation targets Java 21. Sandbox escalation is needed for Git metadata, Docker, and Maven cache/network. Commands must not print credentials.

## Remaining work

1. Complete and review all service implementations and Angular flows.
2. Assemble runnable Compose infrastructure and production configuration/runbooks.
3. Run unit/integration tests and full-stack resume/POC validation; fix failures.
4. Review dependency/security findings, secret scan, and browser flows.
5. Record actual validation results here and in an evidence artifact; prepare final PR to main.

Do not claim completion until these checks actually pass. Update this file after each integration milestone.
