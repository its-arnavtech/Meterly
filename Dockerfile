# syntax=docker/dockerfile:1
#
# Meterly backend images. One build stage compiles the whole Maven reactor once; each deployable gets
# its own minimal JRE stage (select with --target / compose `build.target`):
#
#   tenant-service  ingestion-service  metering-service  billing-service  notification-service  migrator
#
#   docker build --target tenant-service -t meterly/tenant-service:dev .
#
# Images run as a non-root user (application files stay root-owned, so the process cannot modify its own
# code), contain no build tooling, and use Spring Boot's layered extraction so a code change only
# invalidates the thin "application" layer (dependencies are cached layers).

ARG JAVA_MAJOR=21

# ------------------------------------------------------------------------------------------------ build
FROM maven:3.9-eclipse-temurin-${JAVA_MAJOR} AS build
WORKDIR /workspace

# 1) POMs only: resolves plugins + dependencies into the cache mount. This layer is reused until a pom
#    changes. Packaging the source-less reactor (tests and repackaging skipped) exercises exactly the
#    plugins the real build needs, which `dependency:go-offline` cannot do for reactor SNAPSHOT modules.
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY --parents */pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -q -Dmaven.test.skip=true -Dspring-boot.repackage.skip=true package

# 2) Sources: full build (tests run in CI, not in the image build).
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -DskipTests package

# 3) Spring Boot layered extraction (dependencies -> loader -> snapshot deps -> application).
RUN set -eu; \
    extract() { java -Djarmode=tools -jar "$2" extract --layers --launcher --destination "/layers/$1"; }; \
    for svc in tenant-service ingestion-service metering-service billing-service notification-service; do \
        extract "$svc" "$svc/target/$svc.jar"; \
    done; \
    extract migrator meterly-db/target/meterly-db-1.0.0-SNAPSHOT-exec.jar; \
    ls /layers/*

# ---------------------------------------------------------------------------------------------- runtime
FROM eclipse-temurin:${JAVA_MAJOR}-jre-alpine AS runtime
ARG VERSION=1.0.0-SNAPSHOT
ARG REVISION=unknown
ARG CREATED=unknown
LABEL org.opencontainers.image.vendor="Meterly" \
      org.opencontainers.image.authors="Arnav Kumar" \
      org.opencontainers.image.source="https://github.com/its-arnavtech/Meterly" \
      org.opencontainers.image.documentation="https://github.com/its-arnavtech/Meterly/blob/main/README.md" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:21-jre-alpine"
# Fixed numeric UID/GID so Kubernetes `runAsNonRoot` can verify it without a passwd lookup.
RUN addgroup -S -g 10001 meterly \
 && adduser -S -D -H -u 10001 -G meterly -h /app -s /sbin/nologin meterly
WORKDIR /app
# Heap sized from the container memory limit; die fast on OOM so the orchestrator restarts the pod
# instead of leaving a half-alive JVM. /tmp is the only writable path (emptyDir in Kubernetes).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp" \
    SPRING_PROFILES_ACTIVE=prod
USER 10001:10001
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# ----------------------------------------------------------------------------------------------- tenant
FROM runtime AS tenant-service
LABEL org.opencontainers.image.title="meterly-tenant-service" \
      org.opencontainers.image.description="Tenants, users, JWT issuer + JWKS, plans, subscriptions, resources, API keys"
COPY --from=build /layers/tenant-service/dependencies/ ./
COPY --from=build /layers/tenant-service/spring-boot-loader/ ./
COPY --from=build /layers/tenant-service/snapshot-dependencies/ ./
COPY --from=build /layers/tenant-service/application/ ./
EXPOSE 8081

# -------------------------------------------------------------------------------------------- ingestion
FROM runtime AS ingestion-service
LABEL org.opencontainers.image.title="meterly-ingestion-service" \
      org.opencontainers.image.description="Usage-event ingestion: API-key auth, rate limiting, idempotency, Kafka publish"
COPY --from=build /layers/ingestion-service/dependencies/ ./
COPY --from=build /layers/ingestion-service/spring-boot-loader/ ./
COPY --from=build /layers/ingestion-service/snapshot-dependencies/ ./
COPY --from=build /layers/ingestion-service/application/ ./
EXPOSE 8082

# --------------------------------------------------------------------------------------------- metering
FROM runtime AS metering-service
LABEL org.opencontainers.image.title="meterly-metering-service" \
      org.opencontainers.image.description="Idempotent usage ledger consumer, Redis counters, live STOMP updates, reconciliation"
COPY --from=build /layers/metering-service/dependencies/ ./
COPY --from=build /layers/metering-service/spring-boot-loader/ ./
COPY --from=build /layers/metering-service/snapshot-dependencies/ ./
COPY --from=build /layers/metering-service/application/ ./
EXPOSE 8083

# ---------------------------------------------------------------------------------------------- billing
FROM runtime AS billing-service
LABEL org.opencontainers.image.title="meterly-billing-service" \
      org.opencontainers.image.description="Period-close billing, tiered BigDecimal pricing, idempotent invoices, outbox relay"
COPY --from=build /layers/billing-service/dependencies/ ./
COPY --from=build /layers/billing-service/spring-boot-loader/ ./
COPY --from=build /layers/billing-service/snapshot-dependencies/ ./
COPY --from=build /layers/billing-service/application/ ./
EXPOSE 8084

# ----------------------------------------------------------------------------------------- notification
FROM runtime AS notification-service
LABEL org.opencontainers.image.title="meterly-notification-service" \
      org.opencontainers.image.description="Idempotent invoice/threshold notifications via a pluggable email provider"
COPY --from=build /layers/notification-service/dependencies/ ./
COPY --from=build /layers/notification-service/spring-boot-loader/ ./
COPY --from=build /layers/notification-service/snapshot-dependencies/ ./
COPY --from=build /layers/notification-service/application/ ./
EXPOSE 8085

# --------------------------------------------------------------------------------------------- migrator
# One-shot Flyway run as the schema owner; exits 0 on success. Not a server: no port, no probes.
FROM runtime AS migrator
LABEL org.opencontainers.image.title="meterly-migrator" \
      org.opencontainers.image.description="One-shot Flyway schema migrator (runs as the schema owner, then exits)"
COPY --from=build /layers/migrator/dependencies/ ./
COPY --from=build /layers/migrator/spring-boot-loader/ ./
COPY --from=build /layers/migrator/snapshot-dependencies/ ./
COPY --from=build /layers/migrator/application/ ./
