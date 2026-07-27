# Meterly Kubernetes deployment

The base translates the local Compose topology into Kubernetes Deployments,
Services, one non-sensitive ConfigMap, and a runtime-generated Secret. No
credential value is stored in Git.

For a local kind cluster, first create a secret named `meterly-secrets` from the
ignored root `.env` with these keys:

- `POSTGRES_PASSWORD` (owner password)
- `SPRING_DATASOURCE_PASSWORD` (runtime `meterly_app` password)
- `SPRING_FLYWAY_PASSWORD` (owner password)
- `SPRING_FLYWAY_APP_PASSWORD` (runtime password)
- `JWT_SECRET`
- `INTERNAL_SERVICE_KEY`

Then apply `k8s/base` with Kustomize. Images use the fixed local tag `phase3`
and `IfNotPresent` so `kind load docker-image` can supply exactly the tested
build without relying on an external registry.

The kind proof uses ephemeral `emptyDir` data volumes deliberately. A production
cluster must supply a StorageClass-backed PostgreSQL/Redis persistence policy
and an external secret manager before this base is promoted.
