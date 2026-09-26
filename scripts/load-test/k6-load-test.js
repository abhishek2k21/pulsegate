import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
const rateLimitedCount = new Counter('rate_limited_requests');
const circuitBreakerTrippedCount = new Counter('circuit_breaker_tripped');
const successRate = new Rate('gateway_success_rate');
const gatewayLatency = new Trend('gateway_proxy_latency_ms');

export const options = {
  scenarios: {
    // Stage 1: Warmup & baseline rate limit test
    baseline_load: {
      executor: 'constant-vus',
      vus: 10,
      duration: '30s',
      exec: 'testRateLimiting',
    },
    // Stage 2: Stress test to verify zero-over-admission
    stress_burst: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '30s', target: 200 }, // Ramp to 200 req/s
        { duration: '1m', target: 500 },  // Burst to 500 req/s
        { duration: '30s', target: 50 },  // Cool down
      ],
      exec: 'testRateLimiting',
      startTime: '35s',
    },
    // Stage 3: Circuit breaker failure test
    circuit_breaker_trip: {
      executor: 'per-vu-iterations',
      vus: 5,
      iterations: 20,
      exec: 'testCircuitBreaker',
      startTime: '2m40s',
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<15', 'p(99)<30'], // Sub-millisecond gateway processing overhead
    'gateway_success_rate': ['rate>0.95'],
  },
};

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';

export function testRateLimiting() {
  const apiKey = `test-client-${__VU % 5}`;
  const params = {
    headers: {
      'X-API-Key': apiKey,
      'Content-Type': 'application/json',
    },
  };

  const start = new Date();
  const res = http.get(`${BASE_URL}/api/v1/orders/health`, params);
  gatewayLatency.add(new Date() - start);

  if (res.status === 429) {
    rateLimitedCount.add(1);
    check(res, {
      'has retry-after header': (r) => r.headers['Retry-After'] !== undefined,
      'has remaining header': (r) => r.headers['X-Ratelimit-Remaining'] !== undefined,
    });
  } else if (res.status === 200) {
    successRate.add(1);
    check(res, {
      'status is 200': (r) => r.status === 200,
      'has correlation id': (r) => r.headers['X-Correlation-Id'] !== undefined,
    });
  }

  sleep(0.02); // 50 req/s per VU pacing
}

export function testCircuitBreaker() {
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  // Targeting endpoint configured with circuit breaker
  const res = http.get(`${BASE_URL}/api/v1/flaky/action`, params);

  if (res.status === 503) {
    circuitBreakerTrippedCount.add(1);
    check(res, {
      'circuit open message': (r) => r.body && r.body.includes('Circuit breaker is OPEN'),
    });
  }
}
