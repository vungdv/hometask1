# Polaris External API Performance & Contract Test Suite (k6)

This directory contains automated k6 performance and contract validation tests for the Polaris service.

## Test Scope

The k6 test suite (`api-test.js`) verifies:
1. **Catalog Domain Endpoints**:
   - `GET /api/v1/products`: Product listing & search verification (200 OK).
   - `GET /api/v1/products/sku/NG-EARBUD-01`: Exact SKU lookup (200 OK).
   - `GET /api/v1/products/sku/NON-EXISTENT`: Non-existent SKU lookup returning RFC 7807 ProblemDetail (404 Not Found).
2. **Order Domain Endpoints**:
   - `GET /api/v1/orders/ORD-1002/status`: Order status retrieval (200 OK, `CONFIRMED`).
   - `GET /api/v1/orders/ORD-9999/status`: Missing order lookup returning RFC 7807 ProblemDetail (404 Not Found).
   - `POST /api/v1/orders/ORD-1005/cancel`: Attempting to cancel a delivered order returning RFC 7807 ProblemDetail (409 Conflict, "Order State Conflict").
3. **Observability & Distributed Tracing**:
   - Injects W3C standard `traceparent` headers (`00-${traceId}-${spanId}-01`).
   - Validates that the application propagates and echoes the matching `X-Trace-Id` response header.
4. **SLO / SLA Thresholds**:
   - `http_req_duration: ['p(95)<200']` (95th percentile latency must be under 200ms).
   - `checks: ['rate>0.95']` (check success rate must be greater than 95%).

---

## Running the Tests

### Option 1: Using Local k6

If you have `k6` installed locally:

```bash
# Basic run against default https://polaris.local
k6 run tests/k6/api-test.js

# Target local direct HTTP port (if running without TLS proxy)
k6 run -e BASE_URL=http://localhost:8080 tests/k6/api-test.js

# Provide an explicit JWT bearer token
k6 run -e AUTH_TOKEN="<your_jwt_token>" tests/k6/api-test.js

# Custom VUs and duration
k6 run -e VUS=10 -e DURATION=30s tests/k6/api-test.js
```

### Option 2: Using Docker

Run k6 via Docker container without installing local tooling:

```bash
# Pipe script via standard input with host networking
docker run --network host --rm -i grafana/k6 run - < tests/k6/api-test.js

# Passing environment variables to the container
docker run --network host --rm -i \
  -e BASE_URL=https://polaris.local \
  -e VUS=10 \
  -e DURATION=15s \
  grafana/k6 run - < tests/k6/api-test.js
```

---

## Configuration Options (Environment Variables)

| Variable | Default | Description |
|---|---|---|
| `BASE_URL` | `https://polaris.local` | Base URL of the Polaris API gateway |
| `KEYCLOAK_URL` | `https://id.polaris.local/realms/polaris/protocol/openid-connect/token` | Keycloak OpenID Connect token URL |
| `CLIENT_ID` | `polaris-local` | Keycloak OAuth2 client ID |
| `USERNAME` | `testuser` | Keycloak test username |
| `PASSWORD` | `testpass` | Keycloak test user password |
| `AUTH_TOKEN` | `""` | Optional direct Bearer JWT (bypasses Keycloak login call) |
| `VUS` | `5` | Number of concurrent virtual users |
| `DURATION` | `10s` | Test run duration |
