# Meterly Kubernetes deployment

The base translates the local Compose topology into Kubernetes Deployments,
Services, non-sensitive generated ConfigMaps, and a runtime-generated Secret.
It includes Keycloak, the notification-service, Prometheus, and a provisioned
Grafana dashboard. No credential value is stored in Git.

For a local kind cluster, first create a secret named `meterly-secrets` from the
ignored root `.env` with these keys:

- `POSTGRES_PASSWORD` (owner password)
- `SPRING_DATASOURCE_PASSWORD` (runtime `meterly_app` password)
- `SPRING_FLYWAY_PASSWORD` (owner password)
- `SPRING_FLYWAY_APP_PASSWORD` (runtime password)
- `JWT_SECRET`
- `INTERNAL_SERVICE_KEY`
- `KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD`
- `KEYCLOAK_ADMIN_CLIENT_SECRET`

Then apply `k8s/base` with Kustomize. Images use the fixed local tag `phase3`
and `IfNotPresent` so `kind load docker-image` can supply exactly the tested
build without relying on an external registry.

The `keycloak-user-profile` Job runs after Keycloak is reachable and registers
the admin-only `tenant_id` and `role` managed attributes before onboarding.
Prometheus scrapes `/actuator/prometheus` on all five backend Services; Grafana
loads the immutable `Meterly Overview` dashboard from its generated ConfigMap.

The kind proof uses ephemeral `emptyDir` data volumes deliberately. A production
cluster must supply a StorageClass-backed PostgreSQL/Redis persistence policy
and an external secret manager before this base is promoted.
