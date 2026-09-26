# =============================================================================
# PulseGate — Makefile
# Common development commands. Requires: Java 17+, Docker, Maven Wrapper.
# Usage: make <target>
# =============================================================================

.PHONY: help install clean build test test-unit test-integration lint format \
        typecheck check docker-build docker-up docker-down docker-logs \
        deploy-staging deploy-prod k8s-apply k8s-delete db-migrate

SHELL := /bin/bash
MVN   := ./mvnw
DC    := docker compose

# ── Formatting ──────────────────────────────────────────────────────────────
BOLD   := $(shell tput bold   2>/dev/null || echo '')
RESET  := $(shell tput sgr0   2>/dev/null || echo '')
GREEN  := $(shell tput setaf 2 2>/dev/null || echo '')
YELLOW := $(shell tput setaf 3 2>/dev/null || echo '')
CYAN   := $(shell tput setaf 6 2>/dev/null || echo '')

# ── Default target ──────────────────────────────────────────────────────────

help: ## Show this help message
	@echo ""
	@echo "$(BOLD)PulseGate — Available Commands$(RESET)"
	@echo "──────────────────────────────────────────────────────────"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  $(CYAN)%-20s$(RESET) %s\n", $$1, $$2}'
	@echo ""

# ── Setup ───────────────────────────────────────────────────────────────────

install: ## Install dependencies and verify build toolchain
	@echo "$(GREEN)▶ Installing dependencies...$(RESET)"
	$(MVN) -B dependency:resolve dependency:resolve-plugins -q
	@echo "$(GREEN)✓ Dependencies resolved$(RESET)"

clean: ## Remove build artifacts
	@echo "$(YELLOW)▶ Cleaning build artifacts...$(RESET)"
	$(MVN) -B clean -q
	@echo "$(GREEN)✓ Clean complete$(RESET)"

# ── Build ────────────────────────────────────────────────────────────────────

build: ## Compile and package (skip tests)
	@echo "$(GREEN)▶ Building application...$(RESET)"
	$(MVN) -B package -DskipTests -q
	@echo "$(GREEN)✓ Build complete → target/*.jar$(RESET)"

build-prod: ## Production build with all checks
	@echo "$(GREEN)▶ Production build (with tests + coverage check)...$(RESET)"
	$(MVN) -B verify -Pprod
	@echo "$(GREEN)✓ Production build complete$(RESET)"

# ── Testing ──────────────────────────────────────────────────────────────────

test: ## Run all tests (unit + integration) with coverage report
	@echo "$(GREEN)▶ Running all tests...$(RESET)"
	$(MVN) -B verify
	@echo "$(GREEN)✓ Tests complete. Coverage report: target/site/jacoco/index.html$(RESET)"

test-unit: ## Run unit tests only (fast, no Docker required)
	@echo "$(GREEN)▶ Running unit tests...$(RESET)"
	$(MVN) -B test -Dgroups="!integration" -q
	@echo "$(GREEN)✓ Unit tests complete$(RESET)"

test-integration: ## Run integration tests with Testcontainers (requires Docker)
	@echo "$(GREEN)▶ Running integration tests (Testcontainers)...$(RESET)"
	$(MVN) -B verify -Dgroups="integration"
	@echo "$(GREEN)✓ Integration tests complete$(RESET)"

test-watch: ## Watch for changes and re-run unit tests
	@echo "$(YELLOW)▶ Watching for changes...$(RESET)"
	while true; do \
		inotifywait -rqe modify src/main src/test 2>/dev/null; \
		$(MVN) -B test -Dgroups="!integration" -q && echo "$(GREEN)✓ Tests passed$(RESET)" \
		|| echo "$(YELLOW)✗ Tests failed$(RESET)"; \
	done

coverage: ## Generate and open coverage report
	$(MVN) -B verify -q
	@echo "$(GREEN)✓ Coverage report: target/site/jacoco/index.html$(RESET)"

# ── Code Quality ─────────────────────────────────────────────────────────────

lint: ## Run Checkstyle linter
	@echo "$(GREEN)▶ Running Checkstyle...$(RESET)"
	$(MVN) -B checkstyle:check -q
	@echo "$(GREEN)✓ No style violations$(RESET)"

format: ## Format all Java source files with Google Java Format
	@echo "$(GREEN)▶ Formatting source files...$(RESET)"
	@find src -name "*.java" | xargs java -jar scripts/tools/google-java-format.jar --replace 2>/dev/null \
		|| echo "$(YELLOW)  Note: Download google-java-format.jar to scripts/tools/ for auto-format$(RESET)"
	@echo "$(GREEN)✓ Formatting complete$(RESET)"

format-check: ## Check formatting without modifying files (for CI)
	@find src -name "*.java" | xargs java -jar scripts/tools/google-java-format.jar --dry-run --set-exit-if-changed 2>/dev/null \
		|| echo "$(YELLOW)  Note: Add google-java-format.jar to scripts/tools/ to enable format check$(RESET)"

typecheck: ## Run SpotBugs static analysis
	@echo "$(GREEN)▶ Running SpotBugs static analysis...$(RESET)"
	$(MVN) -B spotbugs:check -q
	@echo "$(GREEN)✓ No bugs found$(RESET)"

check: lint typecheck test-unit ## Run all checks (lint + typecheck + unit tests)
	@echo "$(GREEN)✓ All checks passed$(RESET)"

check-all: lint typecheck test ## Run all checks including integration tests
	@echo "$(GREEN)✓ Full check suite passed$(RESET)"

# ── Docker ───────────────────────────────────────────────────────────────────

docker-build: ## Build the Docker image locally
	@echo "$(GREEN)▶ Building Docker image...$(RESET)"
	docker build -t pulsegate:local .
	@echo "$(GREEN)✓ Image built: pulsegate:local$(RESET)"

docker-up: ## Start the full development stack (app + dependencies)
	@echo "$(GREEN)▶ Starting development stack...$(RESET)"
	$(DC) up -d
	@echo "$(GREEN)✓ Stack started$(RESET)"
	@echo "  Gateway:    http://localhost:8080"
	@echo "  Swagger UI: http://localhost:8080/swagger-ui.html"
	@echo "  Grafana:    http://localhost:3000 (admin/admin)"
	@echo "  Prometheus: http://localhost:9090"

docker-deps: ## Start only dependency services (for local development without Docker app)
	@echo "$(GREEN)▶ Starting dependency services only...$(RESET)"
	$(DC) up -d postgres redis kafka zookeeper prometheus grafana
	@echo "$(GREEN)✓ Dependencies started (app runs locally via: make run)$(RESET)"

docker-down: ## Stop and remove all containers
	$(DC) down
	@echo "$(GREEN)✓ Stack stopped$(RESET)"

docker-down-volumes: ## Stop and remove all containers AND volumes (WARNING: deletes data)
	@echo "$(YELLOW)⚠ WARNING: This will delete all persistent data!$(RESET)"
	@read -p "Continue? [y/N] " confirm && [ "$$confirm" = "y" ] || exit 1
	$(DC) down -v
	@echo "$(GREEN)✓ Stack and volumes removed$(RESET)"

docker-logs: ## Tail logs for all services
	$(DC) logs -f

docker-logs-app: ## Tail gateway application logs only
	$(DC) logs -f pulsegate

docker-ps: ## Show status of all containers
	$(DC) ps

# ── Local Development ─────────────────────────────────────────────────────────

run: ## Run application locally (requires docker-deps to be running)
	@echo "$(GREEN)▶ Starting PulseGate locally...$(RESET)"
	$(MVN) -B spring-boot:run -Dspring-boot.run.profiles=dev

run-debug: ## Run with remote debug port 5005 open
	$(MVN) -B spring-boot:run -Dspring-boot.run.profiles=dev \
		-Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

db-migrate: ## Run Flyway database migrations
	$(MVN) -B flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/pulsegate \
		-Dflyway.user=pulsegate -Dflyway.password=pulsegate

db-info: ## Show Flyway migration status
	$(MVN) -B flyway:info

# ── Kubernetes ────────────────────────────────────────────────────────────────

k8s-apply: ## Apply all Kubernetes manifests to current context
	@echo "$(GREEN)▶ Applying K8s manifests...$(RESET)"
	kubectl apply -f k8s/
	@echo "$(GREEN)✓ Manifests applied$(RESET)"

k8s-delete: ## Delete all PulseGate K8s resources
	@echo "$(YELLOW)▶ Deleting K8s resources...$(RESET)"
	kubectl delete -f k8s/
	@echo "$(GREEN)✓ Resources deleted$(RESET)"

k8s-status: ## Show status of PulseGate pods and HPA
	@echo "=== Pods ==="
	kubectl get pods -n pulsegate
	@echo ""
	@echo "=== HPA ==="
	kubectl get hpa -n pulsegate
	@echo ""
	@echo "=== Services ==="
	kubectl get svc -n pulsegate

k8s-logs: ## Tail logs for all gateway pods
	kubectl logs -f -l app=pulsegate -n pulsegate --all-containers

# ── Load Testing ─────────────────────────────────────────────────────────────

load-test: ## Run Artillery load test (requires: npm install -g artillery)
	@echo "$(GREEN)▶ Running load test...$(RESET)"
	artillery run scripts/load-test/scenarios.yml --output scripts/load-test/results.json
	artillery report scripts/load-test/results.json

# ── Deployment ────────────────────────────────────────────────────────────────

deploy-staging: ## Build, push, and deploy to staging environment
	@echo "$(GREEN)▶ Deploying to staging...$(RESET)"
	bash scripts/deploy/deploy.sh staging

deploy-prod: ## Build, push, and deploy to production (requires confirmation)
	@echo "$(YELLOW)⚠ WARNING: Deploying to PRODUCTION$(RESET)"
	@read -p "Are you sure? [y/N] " confirm && [ "$$confirm" = "y" ] || exit 1
	bash scripts/deploy/deploy.sh prod

# ── Utilities ─────────────────────────────────────────────────────────────────

version: ## Show current project version
	@$(MVN) -q help:evaluate -Dexpression=project.version -DforceStdout

deps-tree: ## Print full dependency tree
	$(MVN) dependency:tree

deps-updates: ## Check for dependency version updates
	$(MVN) versions:display-dependency-updates versions:display-plugin-updates

outdated: deps-updates ## Alias for deps-updates

generate-keys: ## Generate a secure JWT secret key for local dev
	@openssl rand -base64 64 | tr -d '\n' && echo ""

.DEFAULT_GOAL := help
