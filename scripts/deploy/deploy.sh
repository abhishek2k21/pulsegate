#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/deploy.sh — Deploy PulseGate to target environment
# Usage: bash scripts/deploy/deploy.sh [staging|prod]
# =============================================================================

set -euo pipefail

ENVIRONMENT="${1:-staging}"
GIT_SHA=$(git rev-parse --short HEAD)
IMAGE_TAG="${GIT_SHA}"
REGISTRY="${ECR_REGISTRY:-ghcr.io/abhishek2k21/pulsegate}"

GREEN='\033[0;32m'; YELLOW='\033[0;33m'; RED='\033[0;31m'; NC='\033[0m'
info() { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

info "=== Deploying PulseGate → ${ENVIRONMENT} (sha: ${GIT_SHA}) ==="

# ── 1. Build production JAR ────────────────────────────────────────────────
info "Building production JAR..."
./mvnw -B package -DskipTests -Pprod -q
info "Build complete"

# ── 2. Build Docker image ──────────────────────────────────────────────────
info "Building Docker image: ${REGISTRY}:${IMAGE_TAG}"
docker build \
  --build-arg BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --build-arg VCS_REF="${GIT_SHA}" \
  -t "${REGISTRY}:${IMAGE_TAG}" \
  -t "${REGISTRY}:${ENVIRONMENT}-latest" \
  .
info "Image built"

# ── 3. Push to registry ────────────────────────────────────────────────────
info "Pushing to registry..."
docker push "${REGISTRY}:${IMAGE_TAG}"
docker push "${REGISTRY}:${ENVIRONMENT}-latest"
info "Image pushed"

# ── 4. Environment-specific deployment ────────────────────────────────────
case "${ENVIRONMENT}" in
  staging)
    info "Deploying to staging Kubernetes cluster..."
    kubectl config use-context pulsegate-staging 2>/dev/null || warn "Context not found — using current context"
    kubectl set image deployment/pulsegate pulsegate="${REGISTRY}:${IMAGE_TAG}" -n pulsegate
    kubectl rollout status deployment/pulsegate -n pulsegate --timeout=3m
    ;;
  prod)
    info "Deploying to production Kubernetes cluster..."
    kubectl config use-context pulsegate-prod 2>/dev/null || warn "Context not found — using current context"
    kubectl set image deployment/pulsegate pulsegate="${REGISTRY}:${IMAGE_TAG}" -n pulsegate
    kubectl rollout status deployment/pulsegate -n pulsegate --timeout=5m
    ;;
  *)
    echo "Unknown environment: ${ENVIRONMENT}. Use 'staging' or 'prod'"
    exit 1
    ;;
esac

info ""
info "=== Deployment Complete ==="
info "Image: ${REGISTRY}:${IMAGE_TAG}"
info "Environment: ${ENVIRONMENT}"
info ""
info "Verify with: kubectl get pods -n pulsegate"
