#!/usr/bin/env bash
# OBS-DIA-1 / OBS-TRC-1 verification (plan O2). Needs the stack up (make up) and curl + python3.
# Checks: datasource health and correlation jsonData, span-metric / service-graph series and
# exemplars in Prometheus, a failing request -> trace -> its logs, and a placed order's trace
# spanning HTTP -> outbox -> Kafka produce -> consumer.
# Usage: scripts/telemetry/correlation-check.sh
# Env:   backends are reached inside polaris-net (scripts/telemetry/lib/net.sh; TELEMETRY_ACCESS=host uses the dev-ports
#        loopback publish). GRAFANA / GRAFANA_AUTH (admin password from .env) PROM TEMPO LOKI overrides; APP (http://localhost:8080)
#        WAIT_SECS (60)  RUN_E2E=1 places orders first via tests/e2e/run-fulfilment.sh
#        ORDER_TRACE_ID=<id> checks that trace instead of searching for a recent order trace
set -uo pipefail
cd "$(dirname "$0")/../.."
. scripts/telemetry/lib/net.sh
GAUTH=$GRAFANA_AUTH; APP=${APP:-http://localhost:8080}
WAIT=${WAIT_SECS:-60}
fail=0
ok()  { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
poll() { # description, command... ; retries until it succeeds or WAIT elapses
  local d=$1; shift; local end=$((SECONDS + WAIT))
  until "$@" >/dev/null 2>&1; do
    [ $SECONDS -ge $end ] && { bad "$d"; return 1; }; sleep 3
  done; ok "$d"
}
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1"; }

# 1. Datasource health and correlation jsonData
for u in prometheus loki tempo; do
  st=$(icurl -s -m 15 -u "$GAUTH" "$GRAFANA/api/datasources/uid/$u/health" | jget "d.get('status')" 2>/dev/null)
  [ "$st" = OK ] && ok "grafana datasource $u health OK" || bad "grafana datasource $u health ($st)"
done
ds() { icurl -s -m 15 -u "$GAUTH" "$GRAFANA/api/datasources/uid/$1" | jget "$2" 2>/dev/null; }
[ "$(ds tempo "d['jsonData']['tracesToLogsV2']['datasourceUid']")" = loki ] && ok "tempo -> loki (trace to logs)" || bad "tempo -> loki"
[ "$(ds tempo "d['jsonData']['tracesToMetrics']['datasourceUid']")" = prometheus ] && ok "tempo -> prometheus (trace to metrics)" || bad "tempo -> prometheus"
[ "$(ds tempo "d['jsonData']['serviceMap']['datasourceUid']")" = prometheus ] && ok "tempo service map" || bad "tempo service map"
[ "$(ds loki "d['jsonData']['derivedFields'][0]['datasourceUid']")" = tempo ] && ok "loki derived field -> tempo" || bad "loki derived field"
[ "$(ds prometheus "d['jsonData']['exemplarTraceIdDestinations'][0]['datasourceUid']")" = tempo ] && ok "prometheus exemplar -> tempo" || bad "prometheus exemplar destination"

# 2. Span-derived metrics and exemplars in Prometheus (generate traffic first)
for i in 1 2 3; do curl -s -o /dev/null -m 5 "$APP/actuator/health"; done
series() { icurl -s -m 10 "$PROM/api/v1/query" --data-urlencode "query=count($1)" | grep -q '"value"'; }
poll "prometheus has traces_spanmetrics_calls_total" series traces_spanmetrics_calls_total
poll "prometheus has traces_service_graph_request_total" series traces_service_graph_request_total
exemplars() {
  icurl -s -m 10 "$PROM/api/v1/query_exemplars" --data-urlencode 'query=traces_spanmetrics_latency_bucket' \
    --data-urlencode "start=$(( $(date +%s) - 3600 ))" --data-urlencode "end=$(date +%s)" | grep -q '"traceID"'
}
poll "prometheus stores exemplars with a trace id" exemplars

# 3. Failing request -> trace (continues the caller's traceparent) -> its logs
TID=$(openssl rand -hex 16)
code=$(curl -s -o /dev/null -m 10 -w '%{http_code}' -H "traceparent: 00-$TID-00f067aa0ba902b7-01" "$APP/actuator/nope")
[ "$code" -ge 400 ] && ok "failing request returned $code" || bad "expected an error status, got $code"
trace_found() { icurl -sf -m 10 "$TEMPO/api/traces/$TID" | grep -q "$TID\|resourceSpans\|batches"; }
poll "tempo has the failing request's trace $TID (traceparent continued)" trace_found
logs_found() {
  icurl -s -m 10 -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query={service_name=~\".+\"} | trace_id=\"$TID\"" \
    --data-urlencode "start=$(( $(date +%s) - 600 ))000000000" | grep -q '"values"'
}
poll "loki has logs carrying trace_id $TID" logs_found

# 4. A placed order's trace: HTTP -> outbox -> Kafka produce -> consumer
[ "${RUN_E2E:-0}" = 1 ] && { ./tests/e2e/run-fulfilment.sh >/dev/null 2>&1 && ok "e2e placed orders" || bad "e2e run"; sleep 15; }
find_order_trace() {
  [ -n "${ORDER_TRACE_ID:-}" ] && { echo "$ORDER_TRACE_ID"; return; }
  icurl -s -m 15 -G "$TEMPO/api/search" --data-urlencode 'q={ name = "http post /api/v1/orders" }' \
    --data-urlencode limit=10 | python3 -c "import sys,json;t=json.load(sys.stdin).get('traces',[]);print(t[0]['traceID'] if t else '')"
}
order_trace_ok() {
  local id; id=$(find_order_trace); [ -n "$id" ] || return 1
  icurl -sf -m 15 "$TEMPO/api/v2/traces/$id" | python3 -c '
import sys,json
d=json.load(sys.stdin); t=d.get("trace",d)
sp=[s for rs in t["resourceSpans"] for ss in rs["scopeSpans"] for s in ss["spans"]]
k=lambda kind,pred: any(s["kind"]==kind and pred(s["name"]) for s in sp)
need={"http server":k("SPAN_KIND_SERVER",lambda n:n.startswith("http ")),
      "outbox publish":k("SPAN_KIND_PRODUCER",lambda n:n.startswith("outbox publish")),
      "kafka produce":k("SPAN_KIND_PRODUCER",lambda n:n.endswith(" send")),
      "kafka consumer":k("SPAN_KIND_CONSUMER",lambda n:n.endswith(" process"))}
miss=[n for n,v in need.items() if not v]
sys.exit(print("missing:",miss) or 1) if miss else sys.exit(0)'
}
poll "order trace spans HTTP -> outbox -> Kafka produce -> consumer" order_trace_ok

echo; [ $fail -eq 0 ] && echo "PASS" || echo "FAIL"
exit $fail
