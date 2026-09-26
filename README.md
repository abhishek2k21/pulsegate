# PulseGate — Distributed API Gateway

[![CI](https://github.com/abhishek2k21/pulsegate/actions/workflows/ci.yml/badge.svg)](https://github.com/abhishek2k21/pulsegate/actions)
[![Coverage](https://img.shields.io/badge/coverage-78%25-brightgreen)]()
[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**PulseGate** is a production-grade, self-hosted API gateway built on **Spring WebFlux** (non-blocking reactive I/O). It provides distributed rate limiting, adaptive circuit breaking, and real-time observability — designed for engineering teams building Java microservices who need a lightweight, observable, extensible gateway without vendor lock-in.

> **Live Demo:** [pulsegate.railway.app](https://pulsegate.railway.app) &nbsp;|&nbsp; **Swagger UI:** `/swagger-ui.html` &nbsp;|&nbsp; **Grafana:** `:3000` (admin/admin)

---

## Why PulseGate?

Most teams reach for Kong or AWS API Gateway before understanding the cost:
- **Kong**: $15K+/year enterprise license for advanced rate limiting and observability
- **AWS API Gateway**: Vendor lock-in, limited circuit breaker support, expensive at scale
- **NGINX Plus**: Requires Lua scripting for custom logic, opaque debugging

PulseGate is a **transparent, Java-native alternative** — every line of code is readable, every metric is exported, and every configuration is a database row.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Client Request                              │
└──────────────────────────────┬──────────────────────────────────┘
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│              PulseGate Filter Pipeline (Spring WebFlux)          │
│                                                                  │
│  [Correlation ID] → [JWT/OAuth2 Auth] → [Rate Limit] →          │
│  [Circuit Breaker] → [Transform] → [Proxy] → [Audit Log]        │
└──────┬──────────────────────────────────────────────────────────┘
       │
       ├── Redis (rate limit state, CB state, route cache invalidation)
       ├── PostgreSQL (route config, policies, analytics)
       ├── Kafka (async request events, CB transition events)
       └── Prometheus + Grafana (real-time metrics)
```

### Rate Limiting: Multi-Algorithm with Redis Lua

```
Token Bucket (bursty traffic)      Sliding Window (strict fairness)
┌──────────────────────┐           ┌─────────────────────────────┐
│ capacity=100 tokens  │           │ requests in last 60s: [..]  │
│ refill: 1/sec        │           │ ZADD key score=now          │
│ Lua: atomic HMSET    │           │ ZREMRANGEBYSCORE old entries │
└──────────────────────┘           └─────────────────────────────┘
         ↓ enforced across all gateway replicas via shared Redis
```

### Circuit Breaker: State Machine with WebSocket Live Dashboard

```
         failure_rate > 50%
CLOSED ─────────────────────► OPEN
   ▲                            │
   │  probe calls succeed       │ wait 30s
   │                            ▼
   └──────────────── HALF_OPEN ◄─── allow 3 probe requests
```

---

## Features

### ✅ Multi-Algorithm Distributed Rate Limiting
- **Token Bucket** — bursty traffic with burst allowance
- **Sliding Window** — strict per-second fairness (zero edge-burst problem)
- **Fixed Window** — simple quota management (free-tier metering)
- Redis Lua scripts ensure **atomic enforcement across N gateway replicas**
- Per-client key strategies: IP, User ID, API Key, or per-route global limit
- Standard rate limit headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

### ✅ Adaptive Circuit Breaker
- Full state machine: CLOSED → OPEN → HALF_OPEN
- Configurable: failure rate, slow call rate, sliding window size, wait duration
- Redis-backed state persistence for **multi-node consensus**
- **WebSocket push** — `ws://gateway/ws/circuit-breakers` for live dashboard
- **SSE stream** — `/admin/circuit-breakers/events` for non-WebSocket clients
- Per-circuit Prometheus gauge: `pulsegate_circuitbreaker_state{name="..."}`

### ✅ Dynamic Route Management
- Routes stored in PostgreSQL — no YAML files, no restarts
- Redis pub/sub cache invalidation: updates propagate to all nodes in **< 100ms**
- Admin REST API: full CRUD with soft-enable/disable
- Glob path matching: `/api/orders/**`, `/api/products/*/details`
- Per-route filter composition: configure which filters apply to each route

### ✅ Production-Grade Observability
- **Prometheus metrics** at `/actuator/prometheus` (Micrometer)
- **Grafana dashboard** (import-ready JSON) — request rate, latency p50/p95/p99, circuit breaker states, rate limit denials
- **Structured logging** with `X-Correlation-ID` threading through all log lines
- **ELK-compatible** JSON log format

### ✅ OAuth 2.0 + JWT Security
- Spring Security OAuth2 Resource Server
- Supports any JWKS-compatible IdP: Keycloak, Auth0, AWS Cognito, Google
- Per-route auth config: `public`, `jwt-required`, `oauth2`
- RBAC: Admin API requires `ADMIN` scope

---

## Quick Start

### Prerequisites
- Docker Desktop
- Java 17+ (for local development)
- Git

### 1. Clone and Start

```bash
git clone https://github.com/abhishek2k21/pulsegate.git
cd pulsegate
docker compose up -d
```

Wait ~45 seconds for all services to be healthy:

```bash
docker compose ps    # all services should show "healthy"
```

### 2. Verify Gateway is Running

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}
```

### 3. Create Your First Route

```bash
curl -X POST http://localhost:8080/admin/routes \
  -H "Content-Type: application/json" \
  -d '{
    "routeId": "httpbin-route",
    "pathPattern": "/test/**",
    "upstreamUrl": "https://httpbin.org",
    "enabled": true,
    "stripPrefix": 1
  }'
```

### 4. Test Rate Limiting

```bash
# Register a rate limit policy (10 req/min, token bucket)
curl -X POST http://localhost:8080/admin/rate-limit-policies \
  -H "Content-Type: application/json" \
  -d '{"policyName":"demo","algorithm":"TOKEN_BUCKET","limitForPeriod":10,"windowSeconds":60,"keyType":"IP","refillTokens":1}'

# Hit the route 11 times — 11th should get 429
for i in {1..11}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/test/get
done
```

### 5. Watch Circuit Breaker Live

```bash
# Connect WebSocket (requires wscat: npm install -g wscat)
wscat -c ws://localhost:8080/ws/circuit-breakers
# You'll see: {"type":"SNAPSHOT","states":{"cb:httpbin-route":"CLOSED"}}
# Trigger failures to watch state flip to OPEN in real-time
```

### 6. View Metrics

Open: **http://localhost:3000** → admin/admin → PulseGate Dashboard

---

## API Reference

Full Swagger UI: **http://localhost:8080/swagger-ui.html**

| Endpoint | Method | Description |
|---|---|---|
| `/admin/routes` | GET | List all routes |
| `/admin/routes` | POST | Create route |
| `/admin/routes/{id}` | PUT | Update route |
| `/admin/routes/{id}/enable` | PATCH | Enable route |
| `/admin/routes/{id}/disable` | PATCH | Disable route |
| `/admin/circuit-breakers` | GET | All CB states |
| `/admin/circuit-breakers/events` | GET (SSE) | Live state stream |
| `/admin/rate-limit-policies` | GET/POST | Manage RL policies |
| `/ws/circuit-breakers` | WebSocket | Live CB dashboard |
| `/actuator/prometheus` | GET | Prometheus metrics |
| `/actuator/health` | GET | Health check |

---

## Configuration

All configuration via environment variables:

| Variable | Default | Description |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | PostgreSQL host |
| `POSTGRES_DB` | `pulsegate` | Database name |
| `REDIS_HOST` | `localhost` | Redis host |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `JWT_JWKS_URI` | _(empty)_ | OAuth2 JWKS endpoint (optional) |

---

## Kubernetes Deployment

```bash
# Create namespace and secrets
kubectl apply -f k8s/namespace.yml
kubectl create secret generic pulsegate-secrets \
  --from-literal=POSTGRES_PASSWORD=your-password \
  -n pulsegate

# Deploy
kubectl apply -f k8s/

# Verify
kubectl get pods -n pulsegate
# NAME                         READY   STATUS    RESTARTS
# pulsegate-6d8b9f7c4d-xk2p8   1/1     Running   0
# pulsegate-6d8b9f7c4d-m9fn3   1/1     Running   0

# HPA in action
kubectl get hpa -n pulsegate
# NAME            REFERENCE              TARGETS         MINPODS   MAXPODS   REPLICAS
# pulsegate-hpa   Deployment/pulsegate   45%/70% CPU     2         10        2
```

---

## Development

```bash
# Start dependencies only
docker compose up postgres redis kafka -d

# Run application locally
./mvnw spring-boot:run

# Run tests with coverage
./mvnw verify

# View coverage report
open target/site/jacoco/index.html
```

### Running Tests

```bash
# Unit tests only (fast, no Docker required)
./mvnw test -Dgroups="unit"

# Integration tests (requires Docker for Testcontainers)
./mvnw verify -Dgroups="integration"

# All tests + coverage report
./mvnw verify
```

---

## Project Structure

```
src/main/java/com/pulsegate/
├── config/          # Redis, Kafka, Security, WebSocket configuration
├── gateway/
│   ├── filter/      # Plugin filter pipeline (auth, rate-limit, circuit-breaker, log)
│   ├── router/      # Dynamic route cache with Redis pub/sub invalidation
│   └── proxy/       # Reactive WebClient upstream proxy
├── ratelimit/       # Token Bucket, Sliding Window, Fixed Window algorithms
├── circuitbreaker/  # State machine + Redis consensus + WebSocket push
├── admin/           # REST admin API controllers
├── analytics/       # Kafka producer/consumer for request event streaming
├── model/           # Domain models (Route, RateLimitPolicy, CircuitBreakerConfig)
├── repository/      # R2DBC reactive repositories
└── websocket/       # WebSocket handlers for live dashboard
```

---

## Load Test Results

Tested with [Artillery](https://artillery.io/) — 500 virtual users, 60 second ramp-up:

```
┌──────────────────────────────────────────────────┐
│  Test: 500 VU ramp over 60s, sustained 30s       │
│  Rate limit: 100 req/min per IP                   │
├──────────────────────────────────────────────────┤
│  Requests/sec:          487 peak                  │
│  Rate limit accuracy:   100% (0 over-admission)   │
│  Gateway latency p50:   1.2ms                     │
│  Gateway latency p99:   3.8ms                     │
│  Circuit breaker:       Opens within 2s of fault  │
│  Memory under load:     218MB heap                │
└──────────────────────────────────────────────────┘
```

---

## Contributing

This project welcomes contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

Areas actively seeking contributions:
- gRPC upstream routing support
- Redis Cluster mode for high availability
- Kubernetes operator for CRD-based route management
- Grafana dashboard improvements

---

## Author

**Abhishek Kumar** — Backend Software Engineer  
[GitHub](https://github.com/abhishek2k21) · [LinkedIn](https://www.linkedin.com/in/abhishek-kumar-029625240) · [Portfolio](https://abhishek2k21.github.io/my-portfolio/)

---

## License

MIT License — see [LICENSE](LICENSE) for details.
