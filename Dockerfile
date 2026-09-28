# ── Stage 1: Build the Application ─────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Copy pom.xml and pre-fetch dependencies for efficient layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source tree and compile executable JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Stage 2: Hardened Production Runtime ───────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Create unprivileged system user for container security
RUN addgroup -S pulsegate && adduser -S pulsegate -G pulsegate

# Copy the built JAR from builder stage
COPY --from=builder /build/target/pulsegate-*.jar app.jar
RUN chown pulsegate:pulsegate app.jar

USER pulsegate

# JVM ergonomics: container memory limits, low-pause G1GC
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -Djava.security.egd=file:/dev/./urandom"

ENV PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]
