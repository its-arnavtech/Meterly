FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY backend/platform-common/pom.xml backend/platform-common/pom.xml
COPY backend/database-schema/pom.xml backend/database-schema/pom.xml
COPY backend/tenant-service/pom.xml backend/tenant-service/pom.xml
COPY backend/ingestion-service/pom.xml backend/ingestion-service/pom.xml
COPY backend/metering-service/pom.xml backend/metering-service/pom.xml
COPY backend/billing-service/pom.xml backend/billing-service/pom.xml
COPY backend/notification-service/pom.xml backend/notification-service/pom.xml
RUN mvn -B -ntp -pl backend/tenant-service -am dependency:go-offline
COPY backend/platform-common/src backend/platform-common/src
COPY backend/database-schema/src backend/database-schema/src
COPY backend/tenant-service/src backend/tenant-service/src
RUN mvn -B -ntp -pl backend/tenant-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && apk del gnupg sqlite-libs \
    && adduser -D -H -u 10001 meterly
COPY --from=build /workspace/backend/tenant-service/target/tenant-service-*.jar /app.jar
USER meterly
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
