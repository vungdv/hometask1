#!/usr/bin/env bash
# LIVE failure-injection check for O6 (outbox and Kafka visibility). Needs the stack up (make up). Not run in CI.
#
# Real thresholds are 5 minutes of age held for 5 minutes (promtool unit tests cover them), too slow for a live run. This script
# generates a clearly labelled TEST-ONLY copy of OutboxStuck (name TestOutboxStuck_ShortWindow, label test="true", age > 20 s for 30 s),
# loads it through docker/telemetry/prometheus/rules-test/ (git-ignored) and removes it on exit, as scripts/telemetry/alerts-check.sh does.
#
# Scenario: stop the 3 Kafka brokers -> place orders through the gateway (orders are accepted, events pile up in the outbox)
#   -> pending count and oldest-pending age rise (also per event_type) and the test alert fires, email in Mailpit
#   -> metrics continuity (O6c): for CONTINUITY_SECS more of the outage, polaris keeps exporting (its JVM uptime series changes at
#      least every 150 s, OTLP step is 60 s) and the pending count/age series are present at every sample, the age rising
#   -> start Kafka -> outbox drains to 0, the alert resolves, consumers recover.
# Env: PROM AM MAILPIT (localhost defaults), WAIT_SECS (per wait, default 240), BROKERS (default "kafka-1 kafka-2 kafka-3"),
#      CONTINUITY_SECS (default 180; 0 skips the continuity window), DRAIN_WAIT_SECS (default 420, relay backoff caps at 5 min)
set -uo pipefail
cd "$(dirname "$0")/../.."
PROM=${PROM:-http://localhost:9090}; AM=${AM:-http://localhost:9093}; MAILPIT=${MAILPIT:-http://localhost:8025}
WAIT=${WAIT_SECS:-240}; CONTINUITY=${CONTINUITY_SECS:-180}; BROKERS=${BROKERS:-"kafka-1 kafka-2 kafka-3"}
TESTRULES=docker/telemetry/prometheus/rules-test/test-async-short.yml
fail=0
ok()  { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
cleanup() { rm -f "$TESTRULES"; docker kill -s HUP prometheus >/dev/null 2>&1; for b in $BROKERS; do docker start "$b" >/dev/null 2>&1; done; }
trap cleanup EXIT

qval() { curl -s -m 10 "$PROM/api/v1/query" --data-urlencode "query=$1" | python3 -c '
import sys,json
r=json.load(sys.stdin)["data"]["result"]; print(max([float(x["value"][1]) for x in r]) if r else "none")'; }
gt() { # gt <promql> <min>: value exists and is >= min
  v=$(qval "$1"); [ "$v" != none ] && python3 -c "import sys; sys.exit(0 if float('$v')>=float('$2') else 1)"; }
zero() { v=$(qval "$1"); [ "$v" = 0.0 ] || [ "$v" = 0 ]; }
firing() { curl -s "$AM/api/v2/alerts?active=true&silenced=false&inhibited=false" | ALERT=$1 python3 -c '
import sys,json,os
sys.exit(0 if any(x["labels"]["alertname"]==os.environ["ALERT"] for x in json.load(sys.stdin)) else 1)'; }
notfiring() { ! firing "$1"; }
mail() { curl -s "$MAILPIT/api/v1/messages?limit=200" | ALERT=$1 python3 -c '
import sys,json,os
sys.exit(0 if any(os.environ["ALERT"] in m["Subject"] for m in json.load(sys.stdin)["messages"]) else 1)'; }
wait_for() { local d=$1; shift; local end=$((SECONDS + WAIT))
  until "$@" >/dev/null 2>&1; do [ $SECONDS -ge $end ] && { bad "$d (waited ${WAIT}s)"; return 1; }; sleep 5; done
  ok "$d after $((WAIT - (end - SECONDS)))s"; }
# continuity <secs>: polaris keeps exporting for <secs> while Kafka stays down. Samples every 10 s and checks
#  - the JVM series process_uptime_milliseconds{Polaris} changes at least every 150 s (it changes with every OTLP export; the
#    Collector exporter would otherwise just repeat the last value for 5 minutes, so "present" alone proves nothing),
#  - polaris_outbox_backlog_events and polaris_outbox_oldest_pending_age_seconds exist at every sample, backlog stays > 0, and the
#    age ends higher than it started (it grows with the clock while events are stuck).
# A sample gap above 60 s means this host was suspended (a sleeping laptop freezes the whole stack): the window restarts instead of
# blaming the app.
continuity() {
  local secs=$1 end start_age last_up last_change t_prev t_now up age backlog missing=0 stuck=0
  while :; do
    end=$((SECONDS + secs)); start_age=none; last_up=none; last_change=$SECONDS; t_prev=$SECONDS; restart=0
    while [ $SECONDS -lt $end ]; do
      sleep 10; t_now=$SECONDS
      if [ $((t_now - t_prev)) -gt 60 ]; then echo "WARN host paused for $((t_now - t_prev)) s during the window, restarting it"; restart=1; break; fi
      t_prev=$t_now
      up=$(qval 'process_uptime_milliseconds{exported_job="Polaris"}'); age=$(qval 'polaris_outbox_oldest_pending_age_seconds{exported_job="Polaris"}')
      backlog=$(qval 'polaris_outbox_backlog_events{exported_job="Polaris"}')
      [ "$up" != "$last_up" ] && { last_up=$up; last_change=$SECONDS; }
      [ $((SECONDS - last_change)) -gt 150 ] && stuck=1
      { [ "$age" = none ] || [ "$backlog" = none ]; } && missing=1
      [ "$backlog" = 0.0 ] || [ "$backlog" = 0 ] && missing=1
      [ "$start_age" = none ] && start_age=$age
    done
    [ $restart = 1 ] && continue
    break
  done
  [ $stuck = 0 ] && ok "polaris metrics kept exporting for ${secs}s of outage (JVM uptime series advanced at least every 150 s)" \
    || bad "polaris metrics stopped exporting during the outage (JVM uptime series unchanged for more than 150 s)"
  [ $missing = 0 ] && ok "pending count and oldest-pending age present at every sample during the outage" \
    || bad "pending count/age series missing (or backlog 0) at some sample during the outage"
  python3 -c "import sys; sys.exit(0 if '$start_age' != 'none' and '$age' != 'none' and float('$age') >= float('$start_age') + $secs/2 else 1)" \
    && ok "oldest pending age kept rising ($start_age -> $age s)" || bad "oldest pending age did not keep rising ($start_age -> $age s)"
}
healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = healthy ]; }

# ---- preconditions
for b in $BROKERS; do docker start "$b" >/dev/null 2>&1; done
curl -sf "$PROM/-/ready" >/dev/null && curl -sf "$AM/-/ready" >/dev/null && curl -sf "$MAILPIT/api/v1/info" >/dev/null \
  && ok "prometheus, alertmanager, mailpit ready" || { bad "stack not ready"; exit 1; }
wait_for "outbox drained and reported (baseline)" zero 'polaris_outbox_backlog_events{exported_job="Polaris"}' || exit 1
NET=$(docker network ls --filter label=com.docker.compose.network=polaris-net --format '{{.Name}}' | head -1)
[ -n "$NET" ] && ok "polaris-net found" || { bad "polaris-net not found"; exit 1; }
curl -s -X DELETE "$MAILPIT/api/v1/messages" >/dev/null

# ---- test-only short-window copy of OutboxStuck
cat > "$TESTRULES" <<'YAML'
# TEST-ONLY, GENERATED by scripts/telemetry/async-check.sh (shortened window). Do not commit.
groups:
  - name: test-async-short
    rules:
      - alert: TestOutboxStuck_ShortWindow
        expr: polaris_outbox_oldest_pending_age_seconds > 20 and on (exported_job) polaris_outbox_backlog_events > 0
        for: 30s
        labels: {test: "true", severity: page, owner: polaris, service: polaris-outbox}
        annotations: {summary: "TEST-ONLY short window of OutboxStuck", service: polaris-outbox, severity: page}
YAML
docker run --rm --entrypoint promtool -v "$PWD/docker/telemetry/prometheus/rules-test:/r:ro" prom/prometheus:v3.14.0 check rules /r/test-async-short.yml >/dev/null \
  && ok "generated test rules pass promtool" || { bad "generated test rules invalid"; exit 1; }
docker kill -s HUP prometheus >/dev/null; sleep 5
firing TestOutboxStuck_ShortWindow && bad "test alert firing before the failure" || ok "no outbox alert while Kafka is healthy"

# ---- inject: stop Kafka, place orders (accepted because events go to the outbox)
for b in $BROKERS; do docker stop "$b" >/dev/null; done
ok "kafka brokers stopped ($BROKERS)"
LOG=$(mktemp)
perl -e 'alarm 240; exec @ARGV' docker run --rm -i --network "$NET" -v "$PWD/tests/e2e/k6:/scripts:ro" -w /scripts \
  -e API_BASE="${API_BASE:-https://polaris.local}" -e KC_BASE="${KC_BASE:-https://id.polaris.local}" -e KC_ADMIN_USER -e KC_ADMIN_PASSWORD -e E2E_STAFF_USER -e E2E_STAFF_PASSWORD -e E2E_USER -e E2E_PASSWORD -e E2E_EMAIL -e SKU \
  -e BATCH=2 -e TIMEOUT=15 -e POLL_INTERVAL=3 grafana/k6 run fulfilment.js >"$LOG" 2>&1
grep -q 'placed (201)' "$LOG" && ok "orders were placed while Kafka was down (business path unaffected)" || { bad "no order placed (see $LOG)"; tail -20 "$LOG"; }
wait_for "outbox pending count rises" gt 'polaris_outbox_backlog_events{exported_job="Polaris"}' 1
wait_for "outbox oldest pending age rises above 20 s" gt 'polaris_outbox_oldest_pending_age_seconds{exported_job="Polaris"}' 20
wait_for "pending count per event_type is reported" gt 'sum(polaris_outbox_pending_events{event_type!=""})' 1
wait_for "TestOutboxStuck_ShortWindow firing in Alertmanager" firing TestOutboxStuck_ShortWindow
wait_for "email for the alert in Mailpit" mail TestOutboxStuck_ShortWindow
[ "$CONTINUITY" -gt 0 ] && continuity "$CONTINUITY"

# ---- recover
for b in $BROKERS; do docker start "$b" >/dev/null; done
for b in $BROKERS; do wait_for "$b healthy again" healthy "$b"; done
# The relay's retry backoff doubles up to 5 minutes (polaris.outbox.relay.backoff.max), so after a long outage the first retry can be
# 5 minutes away: allow DRAIN_WAIT_SECS (default 420) for the drain.
WAIT_DEFAULT=$WAIT; WAIT=${DRAIN_WAIT_SECS:-420}
wait_for "outbox drains to 0 pending" zero 'polaris_outbox_backlog_events{exported_job="Polaris"}'
WAIT=$WAIT_DEFAULT
wait_for "oldest pending age back to 0" zero 'polaris_outbox_oldest_pending_age_seconds{exported_job="Polaris"}'
wait_for "alert resolved" notfiring TestOutboxStuck_ShortWindow
wait_for "polaris healthy" healthy polaris
rm -f "$LOG"
exit $fail
