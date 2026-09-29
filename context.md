# Meterly implementation handoff

Last updated: 2026-09-21. This file is the entry point for Claude or Codex resuming work.

## Objective and current status

Complete the PDD/POC core application, harden it, and validate the resume claims with real executions. **Production readiness is not yet established.** Do not treat draft results, source code, or passing unit tests as proof of end-to-end behavior or latency.

The starting `main` was `78882ee` (one local foundation commit ahead of origin/main). Claude's seven worktrees were preserved. Most services were skeletons. Notification delivery implementation, frontend scaffolding, and Docker/bootstrap fragments existed in uncommitted worktrees.

## Branches and ownership

**Handover (2026-09-22 00:10):** at the user's direction Claude is now the sole integrator; Codex is stopped.
Codex's drafts were merged into `codex/production-integration` (commits `bab1b3b`…`eb6cea3`) and are the
starting point for the remaining work.

- `codex/production-integration`, C:/Meterly: integration branch (Claude). Shared code, contract, notification
  review, PDD/POC alignment, final validation, PR to main.
- Parallel Claude agents, each in its own worktree branched from the integration branch:
  `claude/tenant-service`, `claude/ingestion-service`, `claude/metering-service`, `claude/billing-service`,
  `claude/web`, `claude/platform` (CI, Kubernetes, Java validation harness, docs).
- `codex/*` and `feature/*` worktrees are historical recovery sources only (all useful content is merged).
  Do not delete them without the user's consent.
- Remote `origin/codex/*` branches from July 2026 are an earlier, separate attempt (different layout); unused.

## Requirements and decisions

- Read `Meterly_PDD.docx`, `Meterly_POC.docx` in full; searchable extractions are in docs/Meterly_PDD.txt and docs/Meterly_POC.txt.
- `docs/api-contract.md` gives precise API behavior and pricing rules; §9 defines the release validation bar. Four core microservices are tenant, ingestion, metering, billing; notification is an optional fifth.
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
