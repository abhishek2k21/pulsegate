# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Copy Maven wrapper and pom first — leverages Docker layer cache
COPY pom.xml .
COPY .mvn .mvn
RUN mvn -B dependency:go-offline -q 2>/dev/null || true

# Copy source and build (skip tests — tests run in CI)
COPY src ./src
RUN mvn -B package -DskipTests -q

# Extract layered JAR for optimal layer caching
RUN java -Djarmode=layertools -jar target/*.jar extract

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Non-root user for security
RUN addgroup -S pulsegate && adduser -S pulsegate -G pulsegate
USER pulsegate

# Copy layers in dependency-change-frequency order (most stable first)
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

# JVM tuning: container-aware heap, optimized GC for throughput
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
