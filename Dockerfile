# ── Production Runtime Image ──────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Non-root user for security
RUN addgroup -S pulsegate && adduser -S pulsegate -G pulsegate

# Copy the pre-built Spring Boot executable JAR
COPY target/pulsegate-*.jar app.jar
RUN chown pulsegate:pulsegate app.jar

USER pulsegate

# JVM tuning: container-aware heap, optimized GC for high-throughput reactive gateway
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
