# 📖 PulseGate Admin API Reference

The PulseGate Administrative REST API exposes programmatic endpoints to configure routes, rate limit policies, inspect circuit breakers, and monitor aggregate telemetry.

Swagger UI is available locally at: `http://localhost:8080/swagger-ui.html`
Raw OpenAPI 3.0 specification: `http://localhost:8080/v3/api-docs`

---

## 1. Route Management (`/admin/routes`)

### List All Routes
- **Endpoint:** `GET /admin/routes`
- **Response:** `200 OK`
```json
[
  {
    "id": 1,
    "routeId": "order-service",
    "pathPattern": "/api/v1/orders/**",
    "upstreamUri": "http://order-service:8081",
    "stripPrefix": true,
    "enabled": true,
    "timeoutMs": 3000,
    "retryCount": 2,
    "rateLimitPolicyId": 1,
    "circuitBreakerConfigId": 1
  }
]
```

### Create / Update Route
- **Endpoint:** `POST /admin/routes`
- **Payload:**
```json
{
  "routeId": "payment-service",
  "pathPattern": "/api/v1/payments/**",
  "upstreamUri": "http://payment-service:8082",
  "stripPrefix": true,
  "enabled": true,
  "timeoutMs": 5000,
  "retryCount": 0,
  "rateLimitPolicyId": 2,
  "circuitBreakerConfigId": 2
}
```
- **Response:** `201 Created`

### Invalidate Route Cache (Immediate Distributed Sync)
- **Endpoint:** `POST /admin/routes/{routeId}/invalidate`
- **Response:** `200 OK`

---

## 2. Rate Limit Policy Admin (`/admin/rate-limits`)

### Register Rate Limit Policy
- **Endpoint:** `POST /admin/rate-limits`
- **Payload:**
```json
{
  "name": "tier-gold-sliding-window",
  "algorithm": "SLIDING_WINDOW",
  "capacity": 500,
  "refillRate": 50,
  "windowSeconds": 60,
  "keyType": "API_KEY"
}
```
- **Response:** `201 Created`

---

## 3. Analytics & Telemetry (`/admin/analytics`)

### Gateway Overview Metrics
- **Endpoint:** `GET /admin/analytics/overview`
- **Response:** `200 OK`
```json
{
  "totalRequests": 1250390,
  "successRequests": 1242000,
  "rateLimitedRequests": 7890,
  "circuitBreakerTripped": 500,
  "avgLatencyMs": 3.4,
  "p99LatencyMs": 14.2,
  "activeWebsocketListeners": 4
}
```

---

## 4. WebSocket Live Stream (`/ws/circuit-breaker`)

Connect to: `ws://localhost:8080/ws/circuit-breaker`
Real-time JSON events emitted on every state transition:
```json
{
  "routeId": "payment-service",
  "previousState": "CLOSED",
  "currentState": "OPEN",
  "failureRate": 0.62,
  "timestamp": "2026-09-27T02:45:00Z",
  "reason": "Failure rate exceeded 50% threshold over 20 calls"
}
```
