# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/logs \
    && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/offershield.jar /app/offershield.jar
USER app
ENV LOG_DIR=/app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/offershield.jar"]
