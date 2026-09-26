# 🚨 PulseGate Incident & Operations Runbook

This runbook guides on-call engineers through troubleshooting and mitigating production issues with PulseGate.

---

## 1. High 429 (Too Many Requests) Spikes

### Symptoms
- Sudden spike in HTTP 429 response codes on the Grafana dashboard.
- Metric alert: `pulsegate_ratelimit_breaches_total > 100/min`.

### Diagnostic Steps
1. Identify the impacted route and client API key from Prometheus:
   ```promql
   sum by (route_id, client_id) (rate(pulsegate_ratelimit_breaches_total[5m]))
   ```
2. Check if a single client is overwhelming the service or if a distributed botnet is attempting credential stuffing.
3. Inspect current policy configuration:
   ```bash
   curl -s http://localhost:8080/admin/rate-limits | jq .
   ```

### Mitigation
- **Legitimate Traffic Surge:** Temporarily raise client tier quota:
  ```bash
  curl -X PUT http://localhost:8080/admin/rate-limits/tier-gold \
    -H "Content-Type: application/json" \
    -d '{"capacity": 1000, "refillRate": 100}'
  ```
- **Malicious Attack:** Block IP address or revoke API key directly via SecurityFilter rules.

---

## 2. Upstream Circuit Breaker Trips (HTTP 503)

### Symptoms
- Clients receive `503 Service Unavailable` with body `"Circuit breaker is OPEN"`.
- WebSocket monitor reports transition: `CLOSED -> OPEN`.

### Diagnostic Steps
1. Verify upstream service health directly:
   ```bash
   curl -I http://order-service:8081/actuator/health
   ```
2. Check upstream response times in Grafana:
   ```promql
   histogram_quantile(0.99, sum(rate(pulsegate_upstream_latency_ms_bucket[5m])) by (le, route_id))
   ```
3. Check pod logs for upstream connection reset or timeout errors.

### Mitigation
1. If upstream service recovered but circuit remains in cooldown, manually force half-open probe:
   ```bash
   curl -X POST http://localhost:8080/admin/circuit-breaker/order-service/reset
   ```
2. If upstream service is down, scale up upstream replica count or redirect route to a failover static backend.

---

## 3. Redis Latency & Connection Issues

### Symptoms
- Gateway request latency increases by > 10ms.
- Logs report: `RedisCommandTimeoutException`.

### Diagnostic Steps
1. Check Redis memory and client connections:
   ```bash
   redis-cli info memory
   redis-cli info clients
   ```
2. Verify Redis CPU utilization (`top` or cloud console).

### Mitigation
- PulseGate is designed with **fail-open** resilience: If Redis is completely unreachable, rate limit filters allow critical traffic while logging warnings rather than hard-failing all customer requests.
- Restart or failover to Redis replica:
  ```bash
  kubectl rollout restart statefulset/redis -n pulsegate
  ```
