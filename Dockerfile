# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# --- Runtime stage -------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system amrkpi && useradd --system --gid amrkpi --home-dir /app amrkpi \
    && mkdir -p /app/data \
    && chown -R amrkpi:amrkpi /app

COPY --from=build /build/target/amr-kpi-harness.jar /app/app.jar

USER amrkpi
EXPOSE 8080

# server.shutdown=graceful (application.yml) plus this SIGTERM grace window lets an
# in-flight load test drain and flush its final rollups instead of losing the tail of a run.
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
