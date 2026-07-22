FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY backend/ingestion-service/pom.xml backend/ingestion-service/pom.xml
COPY backend/metering-billing-service/pom.xml backend/metering-billing-service/pom.xml
RUN mvn -B -ntp -pl backend/metering-billing-service -am dependency:go-offline
COPY backend/metering-billing-service/src backend/metering-billing-service/src
RUN mvn -B -ntp -pl backend/metering-billing-service -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 meterly
USER meterly
COPY --from=build /workspace/backend/metering-billing-service/target/metering-billing-service-*.jar /app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app.jar"]
