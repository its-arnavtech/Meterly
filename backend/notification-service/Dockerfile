FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY backend/platform-common/pom.xml backend/platform-common/pom.xml
COPY backend/database-schema/pom.xml backend/database-schema/pom.xml
COPY backend/tenant-service/pom.xml backend/tenant-service/pom.xml
COPY backend/ingestion-service/pom.xml backend/ingestion-service/pom.xml
COPY backend/metering-service/pom.xml backend/metering-service/pom.xml
COPY backend/billing-service/pom.xml backend/billing-service/pom.xml
COPY backend/notification-service/pom.xml backend/notification-service/pom.xml
COPY backend/platform-common/src backend/platform-common/src
COPY backend/notification-service/src backend/notification-service/src
RUN mvn -B -ntp -pl backend/notification-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && apk del gnupg sqlite-libs \
    && adduser -D -H -u 10001 meterly
WORKDIR /app
COPY --from=build /workspace/backend/notification-service/target/notification-service-*.jar app.jar
USER meterly
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
