# syntax=docker/dockerfile:1

# --- Build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# Warms the Gradle distribution + dependency caches in their own layer, so a source-only
# change doesn't force re-downloading everything on the next build.
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon build -x test

# --- Runtime stage -------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system amrkpi && useradd --system --gid amrkpi --home-dir /app amrkpi \
    && mkdir -p /app/data \
    && chown -R amrkpi:amrkpi /app

COPY --from=build /build/build/libs/amr-kpi-harness.jar /app/app.jar

USER amrkpi
EXPOSE 8080

# server.shutdown=graceful (application.yml) plus this SIGTERM grace window lets an
# in-flight load test drain and flush its final rollups instead of losing the tail of a run.
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
