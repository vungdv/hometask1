# Overview

Observe the test results for important features against the changes.

# K6 - performance

- Should focus on scenarios where data grows over time or business expands (more customers).
- Test results should store in time series database such as InfluxDB and visualize by Grafana.
- Ideally dashboard should have ability to compare the growth of data with performance.

## 1. Load Testing

Purpose: Assess how the system behaves under normal and peak user loads.
Example: Simulate 1000 users browsing product pages simultaneously.

## 2. Stress Testing

Purpose: Push the system beyond its limits to identify breaking points.
Goal: Discover max throughput, CPU/memory thresholds, or error patterns under high stress.

## 3. Soak (Endurance) Testing

Purpose: Evaluate performance over extended periods.
Goal: Detect memory leaks, resource starvation, or database connection issues under sustained load.

## 4. Spike Testing

Purpose: Test the system’s ability to handle sudden and dramatic increases in load.
Example: Sudden surge of 5000 users in 10 seconds (e.g., ticket sales launch).

## 5. Baseline Performance

Purpose: Establish benchmarks for response time, latency, and throughput.
Use case: Compare baseline against future builds or environments (CI/CD, canary releases).

## 6. API Performance and SLA Monitoring

Purpose: Validate that APIs consistently meet response time and error rate SLAs.
K6 + InfluxDB/Grafana: Long-term visibility into performance drift.

## 7. Infrastructure Comparison

Purpose: Compare different deployment setups (e.g., containerized vs. VM, AKS vs. ECS).
Goal: Choose the most efficient infrastructure configuration.

## 8. Multi-region Load Testing

Purpose: Simulate traffic from different geographic locations.
Goal: Identify network latency or CDN effectiveness across regions.

## 9. CI/CD Integration (Regression Performance Testing)

Purpose: Automatically run k6 tests after each deploy.
Goal: Detect performance regressions early and block poor builds.

# 10. Database and Backend Bottleneck Discovery

Purpose: Measure backend impact (e.g., query time, connection pool saturation) under load.
With telemetry (OpenTelemetry/Prometheus): Correlate app and DB metrics with load.

## 11. User Journey Simulation (Business Scenario Testing)

Purpose: Simulate realistic end-to-end user flows (login → browse → checkout).
Goal: Ensure holistic performance under load, not just individual APIs.

## 12. Capacity Planning

Purpose: Determine how much infrastructure you need for a given user base.
Use case: Predict future resource needs as usage scales.
