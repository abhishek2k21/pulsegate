#!/usr/bin/env bash
# =============================================================================
# scripts/dev/setup.sh — One-time developer environment setup
# Run: bash scripts/dev/setup.sh
# =============================================================================

set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
NC='\033[0m'

info()    { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
error()   { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

info "=== PulseGate Developer Setup ==="

# 1. Check Java 17+
if ! java -version 2>&1 | grep -q "17\|18\|19\|20\|21"; then
  error "Java 17+ is required. Install from: https://adoptium.net/"
fi
info "Java: $(java -version 2>&1 | head -1)"

# 2. Check Docker
if ! docker info >/dev/null 2>&1; then
  error "Docker is required and must be running. Install: https://docs.docker.com/get-docker/"
fi
info "Docker: $(docker --version)"

# 3. Copy .env if not present
if [ ! -f .env ]; then
  cp .env.example .env
  warn ".env created from .env.example — review and update secrets before use"
else
  info ".env already exists"
fi

# 4. Make mvnw executable
chmod +x mvnw
info "mvnw made executable"

# 5. Resolve Maven dependencies
info "Resolving Maven dependencies..."
./mvnw -B dependency:resolve dependency:resolve-plugins -q
info "Dependencies resolved"

# 6. Start dependency services
info "Starting dependency services (PostgreSQL, Redis, Kafka, Prometheus, Grafana)..."
docker compose up -d postgres redis kafka zookeeper prometheus grafana
info "Waiting for services to be healthy..."
sleep 10

# 7. Run Flyway migrations
info "Running database migrations..."
./mvnw -B flyway:migrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/pulsegate \
  -Dflyway.user=pulsegate \
  -Dflyway.password=pulsegate -q || warn "Migration failed — DB may not be ready yet"

info ""
info "=== Setup Complete ==="
info ""
info "Next steps:"
info "  make run             # Start the gateway locally"
info "  make test-unit       # Run unit tests"
info "  make docker-up       # Start full stack in Docker"
info ""
info "Access points (after make run):"
info "  Gateway:    http://localhost:8080"
info "  Swagger UI: http://localhost:8080/swagger-ui.html"
info "  Grafana:    http://localhost:3000 (admin/admin)"
info "  Prometheus: http://localhost:9090"
