FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY backend/platform-common/pom.xml backend/platform-common/pom.xml
COPY backend/database-schema/pom.xml backend/database-schema/pom.xml
COPY backend/tenant-service/pom.xml backend/tenant-service/pom.xml
COPY backend/ingestion-service/pom.xml backend/ingestion-service/pom.xml
COPY backend/metering-billing-service/pom.xml backend/metering-billing-service/pom.xml
RUN mvn -B -ntp -pl backend/ingestion-service -am dependency:go-offline
COPY backend/platform-common/src backend/platform-common/src
COPY backend/ingestion-service/src backend/ingestion-service/src
RUN mvn -B -ntp -pl backend/ingestion-service -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 meterly
USER meterly
COPY --from=build /workspace/backend/ingestion-service/target/ingestion-service-*.jar /app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app.jar"]
