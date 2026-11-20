# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# Copy the pom first so dependency resolution is cached across source edits.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /src/target/responses2completions.jar /app/app.jar

# Run unprivileged.
RUN addgroup -S app && adduser -S -G app app
USER app

ENV R2C_PORT=8080 \
    R2C_BIND=0.0.0.0 \
    R2C_LOG_LEVEL=info

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${R2C_PORT}/health" >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]