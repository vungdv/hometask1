# Detail Design: O6c — Async signal reliability follow-ups

- **Plan:** [Operational Observability](../../plan/operational-observability.md), slice **O6c**. **Covers:** OBS-ASY-1, OBS-RES-1.
- **Bounded context:** application/library side (`libs/polaris-outbox`, `polaris`, `polaris-fulfilment-emulator`) plus the already-merged O6b rule, tests, dashboard panel and runbook that consume the contract. The platform change is minimal and contract-driven: one rule expression, one dashboard query, no Collector, Alertmanager or compose change.
- **Status:** Implemented in this change. Contract delta (one new gauge, see §3) is additive.

## 1. Metrics going dark: diagnosis

Report (O6b run): during a Kafka outage the `polaris` OTLP metrics, JVM included, stopped for about 17 minutes. Not reproduced at the time.

### 1.1 How export works (read from the code, Micrometer 1.17.1 / Boot 4.1.1)

- The OTLP registry (`PushMeterRegistry`) publishes from **one** scheduler thread, once per step (60 s). `publish()` reads **every** gauge of the app synchronously on that thread.
- `spring.threads.virtual.enabled=true`, so that thread is a virtual thread (it is why a SIGQUIT thread dump does not list `otlp-metrics-publisher`).
- `PushMeterRegistry` skips a tick while the previous publish is still running. One gauge callback that blocks therefore stops the export of **all** metrics of that app until it returns.
- Before this change the outbox gauges did JDBC reads inside their callbacks (`countPending`, `oldestPendingOccurredAt`, and the grouped per-type query under a `synchronized` lock), with no statement timeout, on that thread. The Kafka consumer metrics (`MicrometerConsumerListener`) only read in-memory Kafka client sensors.

### 1.2 Experiments on the live stack

Evidence files are summarised here; raw logs were kept in the session scratchpad.

| # | Experiment | Result |
|:--|:--|:--|
| E1 | Three cycles of: stop all 3 Kafka brokers, place orders through the gateway (k6, BATCH=3), hold 4 minutes taking a SIGQUIT thread dump of `polaris` each minute (12 dumps), start the brokers, wait healthy, drain. Sampled Prometheus every 10 s for `process_uptime_milliseconds{exported_job="Polaris"}` (changes on every OTLP export), `otelcol_receiver_accepted_metric_points`, `otelcol_receiver_refused_metric_points`, outbox backlog and age | **Not reproduced.** The JVM uptime series advanced every 60 s during every outage. `otelcol_receiver_refused_metric_points` stayed 0, accepted points kept rising. The dumps show the relay thread parked in `KafkaProducer.waitOnMetadata` (bounded by the transport's `max.block.ms`), the only BLOCKED platform thread in each dump is the Kafka coordinator heartbeat thread in its normal wait, and no Hikari starvation messages. Virtual threads (the OTLP publisher) are not listed by a SIGQUIT dump, so the dumps cannot show a blocked publisher; the continuity samples are the evidence |
| E2 | Prometheus history of `up{job="prometheus"}` (Prometheus scraping **itself**), `up{job="otel-collector"}` and Polaris series over the last 11 hours | Every gap longer than 10 minutes in the Polaris series (11.5 min, 26 min, 40 min, 66 min, 80 min, ...) is **identical in the Prometheus self-scrape**. Nothing in the stack was collecting: the Docker VM/host was suspended. A suspended host also expires the Collector exporter's series (it uses wall-clock `metric_expiration`, 5 min) |
| E3 | During E1 the laptop slept for 11 minutes (08:36 to 08:46). Both the sampler and the test script stalled for 695 s, and on wake `polaris_outbox_oldest_pending_age_seconds` jumped by about 700 s (wall clock) while the JVM uptime (monotonic) did not | A host sleep during a Kafka outage looks exactly like "all polaris metrics stopped for N minutes": same symptom, whole stack frozen. It is a plausible explanation of the 17 minutes (the O6b run was done on this machine) |
| E4 | **Blocking-gauge mechanism.** Before the fix: `docker pause polaris-postgres` for 180 s (a hung database, TCP connections stay open, so the JDBC read never returns) | **Reproduced the failure mode.** `process_uptime_milliseconds{Polaris}` stayed frozen at `3614445` for the whole 180 s (the Collector kept re-serving the last value); the emulator, which has no database gauge, kept exporting. Export resumed within one step of `docker unpause` |
| E5 | The same pause after the fix (150 s) | `polaris` uptime advanced at every step (`118704 → 178701 → 238702`); log: `Outbox metrics query did not finish within PT2S; reporting no value until it does`, then `Outbox metrics query recovered` after unpause |

### 1.3 Conclusion (be explicit)

- **The 17-minute gap is not proven to have a single cause.** Kafka being down did not, in three cycles under order load, stop the export (E1), and the Collector never refused data.
- **Most probable cause of what was observed: the host/VM was suspended** (E2, E3): the gap is indistinguishable and the same gaps exist for series that have nothing to do with Polaris. This cannot be fixed in the application. `async-check.sh` now detects a suspended host and restarts its window instead of blaming the app, and the runbook says to check for it first.
- **A real defect was found and reproduced** (E4): a gauge callback that blocks stops all metric export of the app. A hung or slow database (or any state in which the JDBC read stalls: pool exhaustion, a lock, network) silences JVM and outbox metrics together, which is exactly when `OutboxStuck` is needed. This is a plausible cause of the original incident if the database or the connection pool stalled during that outage; the root cause of the original 17 minutes remains **unconfirmed**.
- The fix below removes the defect regardless of which explanation is right.

## 2. Fix: gauge callbacks never block the publish thread

`OutboxMetrics` (library `polaris-outbox`):

| Before | After |
|:--|:--|
| JDBC reads inside each gauge callback on the publish thread, no timeout, per-type snapshot under `synchronized` | Reads run on one dedicated daemon thread `polaris-outbox-metrics-query`. A callback waits at most `queryTimeout` (2 s) **in total** for a hung query (the budget is counted from the start of that query, so the 4 outbox gauges of one publish share one wait), then returns at once |
| Gauge registration inside the `backlog` callback | Per-type gauges are registered by the query thread when a type is first seen (the O6a review asked for this) |
| One query per scrape for per-type values, none cached for totals | One snapshot (count, oldest, per-type) cached 5 s serves every gauge; a snapshot older than `staleAfter` (30 s) is not reported |
| Database unreachable: exception in callback | Gauges report NaN (not exported), one WARN on the transition, one INFO on recovery. Behaviour matches the O6a note ("if the database is unreachable the gauges are not exported") |
| Default store with no statement timeout | The metrics store gets its own `JdbcTemplate` with a 5 s `queryTimeout` (auto-configuration only; the relay's store is unchanged) |

A hung query only ever occupies the query thread; while it runs no further query is submitted. `OutboxMetrics` is `AutoCloseable` so Spring stops the thread. The 3-argument constructor keeps no cache (reads every sample), which is what the existing tests rely on; production wiring passes the 5 s TTL explicitly.

Kafka client gauges (`kafka.consumer.*`, `kafka.producer.*`) read in-memory sensors and were not changed. `GroupTracker` gauges read a `ConcurrentHashMap`.

## 3. Poison-record signal

### 3.1 Problems (from the O6a/O6b reviews)

1. `GroupTracker` cleared an in-flight entry only on success or skip, so after a partition revoke or a container stop the age grew forever.
2. The `PoisonRecordBlocked` rule needed `lag > 0` to tolerate (1), which missed a blocked **last** record (the position has advanced, lag reads 0) and could still raise a false ticket when a revoked partition's lag series lingered.

### 3.2 Changes

- **Clear on revoke, loss and stop.** `GroupTracker.rebalanceListener()` returns a `ConsumerAwareRebalanceListener` (set with `ContainerProperties.setConsumerRebalanceListener`) that removes the revoked or lost partitions' entries. `ConsumerGroupMetrics` is now an `ApplicationListener<ConsumerStoppedEvent>` and clears the stopped group's tracker. Order's container is a Spring bean (events are published); the emulator builds its containers by hand, so it forwards the stop event itself. Wired in `ShipmentListenerConfiguration` and `OfferListenerRegistrar`.
- **New gauge** (additive contract change): `polaris.kafka.consumer.blocked.record.age`, Prometheus `polaris_kafka_consumer_blocked_record_age_seconds{group}`, seconds. How long the oldest unfinished record has been **held by the consumer**: counted from the first time the container hands that offset to the listener, kept across retries of the same offset, 0 when idle. It does not use the record timestamp and does not use lag, so:
  - a blocked last record at lag 0 is detected;
  - a consumer catching up on an old backlog (record timestamp old, processed in milliseconds) is not flagged, which `oldest.record.age` would;
  - revoke, loss, stop, success and skip all reset it.
- `polaris_kafka_consumer_oldest_record_age_seconds` is unchanged (record age, the backlog-style signal). It stays on the dashboard but no longer drives an alert.
- **Rule** `PoisonRecordBlocked` (platform, minimal): `polaris_kafka_consumer_blocked_record_age_seconds > 300` for 5 m. The lag join and its documented limitation are removed. Same labels, severity, annotations and runbook URL. The dashboard panel *Oldest in-flight record age by group* gets a second query for the blocked age.
- Runbook `poison-record-blocked.md` is rewritten to the new meaning (independent of lag, cleared on revoke/stop).

## 4. Metrics-continuity assertion

`scripts/telemetry/async-check.sh` gains a window after the alert/email assertions while Kafka is still down (`CONTINUITY_SECS`, default 180, 0 skips). It samples Prometheus every 10 s and asserts:

1. `process_uptime_milliseconds{exported_job="Polaris"}` changes at least every 150 s. This is the proof of continued export: the Collector's exporter repeats the last value for 5 minutes, so "the series exists" proves nothing (this is also why E4 looked healthy for the first minutes in a naive check).
2. `polaris_outbox_backlog_events` and `polaris_outbox_oldest_pending_age_seconds` exist at every sample and the backlog stays above 0.
3. The oldest pending age ends at least half the window higher than it started (it rises with the clock while events are stuck).

A sample gap above 60 s means the host was suspended; the window restarts rather than failing (see §1).

## 5. Tests

| Test | Proves |
|:--|:--|
| `OutboxMetricsNonBlockingTest` (6) | A hung store costs the reader at most the query timeout, then nothing for the rest of the publish; a publish pass over a registry with a JVM-like gauge completes while the database hangs; values come back after recovery; last good snapshot served only while fresh, then NaN; failing store reports NaN and recovers; one cached snapshot serves all gauges |
| `ConsumerGroupMetricsTest` (11, 7 new) | Blocked age counts from the first take across retries of one offset, also for a last record; an old record processed promptly is not blocked; a new offset replaces the held one; skip clears; revoke clears only revoked partitions; lost clears; container-stopped event clears that group only |
| `ConsumerGroupMetricsKafkaIntegrationTest` (2, 1 new) | Real broker: a poison record retried with a seek-based backoff raises the blocked age, `container.stop()` (no success, no skip) clears it and the record-age gauge. Checked by mutation: removing the revoke listener and stop event wiring makes it fail |
| `OutboxMetricsH2Test`, `OutboxRelayIntegrationTest`, `OutboxAutoConfigurationTest` | Existing contract tests unchanged and green on the new `OutboxMetrics` |
| `async.test.yml` (promtool) | `PoisonRecordBlocked` fires for a blocked last record at lag 0 and for a group with no lag series, silent for a blip, for a cleared (revoked/stopped/skipped) gauge and for an old-backlog catch-up |
| `async-check.sh` (live) | Kafka-stop scenario with the continuity window: all 22 assertions passed on the rebuilt stack (JVM uptime advanced throughout 180 s of outage, pending count/age present at every sample, age 95 s to 275 s). A first run failed only the drain wait (240 s) because the relay backoff had grown to its 5 minute cap after the longer outage; the drain wait is now 420 s |

## 6. Honest limits

- The original 17-minute gap is **not** root-caused. Evidence points at host suspension (E2, E3); the blocking-gauge defect is real and fixed (E4, E5) and may or may not have contributed.
- A hung JDBC read still ties up one JVM thread (the query thread) until the driver gives up (5 s statement timeout, TCP timeouts for a paused host). That cost is bounded to one thread by design.
- An outbox gauge reports NaN, so it is absent, while the database is unreachable beyond 30 s. `OutboxStuck` cannot fire then, but the database being down is visible through the app's own readiness/DB metrics; `AsyncMetricsMissing` still covers the case where the outbox gauge is absent for 15 minutes.
- Broker-side lag with a dead consumer remains out of scope (O6a limit).
