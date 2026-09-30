#!/usr/bin/env bash
# OBS-RES-1 failure test (plan O1): stop a telemetry component under request load,
# assert business requests keep status 200 and bounded latency, then restart it and
# assert telemetry resumes WITHOUT restarting the collector/apps.
# Usage: scripts/telemetry/resilience-check.sh [tempo|loki|otel-collector ...]
# Env:   URL (default http://localhost:8080/actuator/health), PHASE_SECS (20),
#        MAX_P95_FACTOR (3), MAX_P95_FLOOR_MS (100), PROM (http://localhost:9090)
set -uo pipefail
cd "$(dirname "$0")/../.."
URL=${URL:-http://localhost:8080/actuator/health}
PHASE=${PHASE_SECS:-20}
FACTOR=${MAX_P95_FACTOR:-3}
FLOOR=${MAX_P95_FLOOR_MS:-100}
PROM=${PROM:-http://localhost:9090}
TARGETS=("$@"); [ ${#TARGETS[@]} -eq 0 ] && TARGETS=(tempo loki otel-collector)
fail=0

load() { # $1 seconds, $2 outfile: "<status> <ms>" per request
  local end=$((SECONDS + $1))
  : > "$2"
  while [ $SECONDS -lt $end ]; do
    curl -s -o /dev/null -m 5 -w '%{http_code} %{time_total}\n' "$URL" | awk '{printf "%s %d\n",$1,$2*1000}' >> "$2"
    sleep 0.05
  done
}
stats() { # file -> "n non200 p95"
  awk '{n++; if($1!=200)e++; l[n]=$2} END{asort(l); i=int(n*0.95); if(i<1)i=1; printf "%d %d %d", n, e+0, l[i]}' "$1" 2>/dev/null \
    || sort -k2 -n "$1" | awk '{n++; if($1!=200)e++; l[n]=$2} END{i=int(n*0.95); if(i<1)i=1; printf "%d %d %d", n, e+0, l[i]}'
}
counter() { # metric name (no _total) -> summed value from Prometheus, 0 if absent
  curl -s "$PROM/api/v1/query" --data-urlencode "query=sum($1)" | sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p' | head -1 | grep . || echo 0
}
metric_for() { case $1 in tempo) echo otelcol_exporter_sent_spans;; loki) echo otelcol_exporter_sent_log_records;; otel-collector) echo otelcol_receiver_accepted_spans;; esac; }

# Wait for the file's own request loop to see 200s first.
curl -sf -m 5 -o /dev/null "$URL" || { echo "FAIL business endpoint $URL not reachable"; exit 1; }
load "$PHASE" /tmp/res-base.$$
read -r bn be bp <<<"$(stats /tmp/res-base.$$)"
echo "baseline: requests=$bn non200=$be p95=${bp}ms"
LIMIT=$(( bp * FACTOR )); [ $LIMIT -lt $FLOOR ] && LIMIT=$FLOOR

for t in "${TARGETS[@]}"; do
  echo "== $t =="
  docker compose stop "$t" >/dev/null 2>&1
  load "$PHASE" /tmp/res-down.$$
  read -r n e p <<<"$(stats /tmp/res-down.$$)"
  echo "  $t stopped: requests=$n non200=$e p95=${p}ms (limit ${LIMIT}ms)"
  if [ "$e" -ne 0 ] || [ "$p" -gt "$LIMIT" ]; then echo "  FAIL business impact while $t down"; fail=1; else echo "  PASS business unaffected while $t down"; fi

  M=$(metric_for "$t")
  docker compose start "$t" >/dev/null 2>&1
  # for a restarted collector its own counters reset, so compare against 0 after recovery
  [ "$t" = otel-collector ] && before=0 || before=$(counter "$M")
  for i in $(seq 1 60); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$t" 2>/dev/null)" = healthy ] && break; sleep 2; done
  load "$PHASE" /tmp/res-up.$$
  sleep 15   # let batch flush + a Prometheus scrape happen
  after=$(counter "$M")
  if [ "$t" = loki ]; then
    # Business requests emit few log lines, so prove log resume with a marker record sent
    # over OTLP/HTTP through the collector and read back from Loki.
    marker="res-$$-$(date +%s)"
    curl -s -m 5 -o /dev/null -H 'Content-Type: application/json' "${OTLP:-http://localhost:4318}/v1/logs" -d \
      "{\"resourceLogs\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"resilience-check\"}}]},\"scopeLogs\":[{\"logRecords\":[{\"timeUnixNano\":\"$(date +%s)000000000\",\"severityText\":\"INFO\",\"body\":{\"stringValue\":\"$marker\"}}]}]}]}"
    found=0
    for i in $(seq 1 15); do
      curl -s -G "${LOKI:-http://localhost:3100}/loki/api/v1/query_range" --data-urlencode "query={service_name=\"resilience-check\"} |= \"$marker\"" \
        --data-urlencode "start=$(( $(date +%s) - 300 ))000000000" | grep -q "$marker" && { found=1; break; }
      sleep 2
    done
    if [ $found = 1 ]; then echo "  PASS telemetry resumed (marker log $marker found in Loki), no restart of other components"; else echo "  FAIL marker log not found in Loki after recovery"; fail=1; fi
    continue
  fi
  if awk -v a="$after" -v b="$before" 'BEGIN{exit !(a>b)}'; then echo "  PASS telemetry resumed ($M $before -> $after), no restart of other components"; else echo "  FAIL telemetry did not resume ($M $before -> $after)"; fail=1; fi
done
rm -f /tmp/res-*.$$
exit $fail
