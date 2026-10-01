#!/usr/bin/env bash
# OBS-EDGE-1 / OBS-TRC-1 (edge hop) / OBS-RES-1 verification for the nginx gateway (plan O2a).
# Needs the stack up (make up) plus curl, python3, docker.
# Checks: a request through https://polaris.local -> one trace rooted at the nginx-gateway span with a
# polaris child, route-named low-cardinality span without a query string, the same trace_id in the
# edge JSON log on stdout and in Loki, no secrets in the log, stub_status internal-only, span metrics.
# RUN_FAILURE=1 also stops polaris (expect edge 502s in logs + span metrics) and the collector
# (nginx must keep serving), then restores both.
# Usage: scripts/telemetry/edge-check.sh
# Env:   TEMPO LOKI PROM (internal addresses via scripts/telemetry/lib/net.sh) WAIT_SECS (60)
set -uo pipefail
cd "$(dirname "$0")/../.."
. scripts/telemetry/lib/net.sh
WAIT=${WAIT_SECS:-60}
fail=0
ok()  { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
poll() { local d=$1; shift; local end=$((SECONDS + WAIT))
  until "$@" >/dev/null 2>&1; do [ $SECONDS -ge $end ] && { bad "$d"; return 1; }; sleep 3; done; ok "$d"; }
gw() { curl -sk -m 20 --resolve polaris.local:443:127.0.0.1 --resolve id.polaris.local:443:127.0.0.1 "$@"; }
edge_lines() { docker logs nginx --since 10m 2>&1 | grep '^{"time"'; }
SECRET="s3cr3t-$(openssl rand -hex 4)"

# 1. Request with query string, OAuth code/state, Authorization and Cookie
code=$(gw -o /dev/null -w '%{http_code}' "https://polaris.local/actuator/health?code=$SECRET&state=$SECRET" \
  -H "Authorization: Bearer $SECRET" -H "Cookie: SESSION=$SECRET")
[ "$code" != 000 ] && ok "request through https://polaris.local returned $code" || bad "gateway not reachable"
sleep 1
LINE=$(edge_lines | grep '"host":"polaris.local"' | tail -1)
TID=$(echo "$LINE" | python3 -c "import sys,json;print(json.loads(sys.stdin.read()).get('trace_id',''))" 2>/dev/null)
SID=$(echo "$LINE" | python3 -c "import sys,json;print(json.loads(sys.stdin.read()).get('span_id',''))" 2>/dev/null)
[ ${#TID} -eq 32 ] && [ ${#SID} -eq 16 ] && ok "edge log on stdout is JSON with trace_id/span_id ($TID)" || bad "edge stdout log line missing trace_id/span_id"
echo "$LINE" | python3 -c "
import sys,json
d=json.loads(sys.stdin.read())
need=['time','trace_id','span_id','host','route','sse','uri','method','status','upstream_status','request_time','upstream_response_time','bytes_sent']
sys.exit(1 if [k for k in need if k not in d] or '?' in d['uri'] else 0)" && ok "edge log has required fields and uri without query string" || bad "edge log fields / uri"
edge_lines | grep -qE "$SECRET|Bearer|SESSION" && bad "secret found in edge stdout log" || ok "no Authorization/cookie/code/state in edge stdout log"

# 2. Same trace_id in Loki (via syslog -> collector), and no secrets there
loki_line() { icurl -s -m 10 -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query={service_name=\"nginx-gateway\"} | trace_id=\"$TID\"" \
  --data-urlencode "start=$(( $(date +%s) - 600 ))000000000" | tee /tmp/edge-loki.$$ | grep -q '"values"'; }
poll "loki has the edge log for trace_id $TID (service nginx-gateway, over syslog)" loki_line
grep -qE "$SECRET|Bearer|SESSION" /tmp/edge-loki.$$ && bad "secret found in Loki edge log" || ok "no secrets in Loki edge log"
rm -f /tmp/edge-loki.$$

# 3. One trace: nginx-gateway root, route-named, polaris descendant of the nginx span
trace_ok() {
  icurl -sf -m 15 "$TEMPO/api/traces/$TID" | SID=$SID python3 -c '
import sys,json,os,base64
d=json.load(sys.stdin); sid=os.environ["SID"]
hx=lambda b: base64.b64decode(b).hex() if b else ""
edge=[];app=[]
for b in d["batches"]:
  svc={a["key"]:list(a["value"].values())[0] for a in b["resource"]["attributes"]}.get("service.name")
  for ss in b["scopeSpans"]:
    for s in ss["spans"]:
      (edge if svc=="nginx-gateway" else app).append((s,svc))
assert len(edge)==1, "expected exactly one nginx-gateway span"
s=edge[0][0]; at={a["key"]:list(a["value"].values())[0] for a in s["attributes"]}
assert hx(s.get("spanId"))==sid and not s.get("parentSpanId"), "nginx span is not the root / span_id mismatch"
assert s["name"]=="polaris:/" and at.get("http.route")=="polaris:/", "span name is not the route"
assert any(hx(x[0].get("parentSpanId"))==sid for x in app), "no polaris child of the nginx span"'
}
poll "one trace rooted at the nginx-gateway span (route-named) with a polaris child" trace_ok
# every attribute of every span of the trace (nginx AND polaris, duplicates included) is scanned raw
no_leak() { icurl -sf -m 15 "$TEMPO/api/traces/$TID" > /tmp/edge-trace.$$ && ! grep -qE "$SECRET|code=|state=" /tmp/edge-trace.$$; }
no_leak && ok "no query string, code/state, Authorization or cookie value in ANY span attribute (raw trace scan)" || bad "secret/query string found in trace $TID"
grep -q '"http.target"' /tmp/edge-trace.$$ && bad "gateway http.target attribute still present" || ok "module's http.target removed by the Collector"
rm -f /tmp/edge-trace.$$

# 4. stub_status: reachable inside, not published to the host
docker exec nginx curl -fsS -o /dev/null http://127.0.0.1:8088/stub_status && ok "stub_status answers on the internal listener" || bad "stub_status internal"
curl -s -m 3 -o /dev/null http://localhost:8088/stub_status && bad "stub_status published to host" || ok "stub_status not reachable from host"
[ "$(docker inspect -f '{{.State.Health.Status}}' nginx)" = healthy ] && ok "nginx compose healthcheck healthy" || bad "nginx healthcheck"

# 5. Span metrics and connection metrics
series() { icurl -s -m 10 "$PROM/api/v1/query" --data-urlencode "query=count($1)" | grep -q '"value"'; }
poll "span metrics exist for service nginx-gateway" series 'traces_spanmetrics_calls_total{service="nginx-gateway"}'
poll "nginx connection metrics scraped (stub_status)" series 'nginx_connections_current'

# 6. Failure modes
if [ "${RUN_FAILURE:-0}" = 1 ]; then
  restore() { docker compose start polaris otel-collector nginx >/dev/null 2>&1; }
  trap restore EXIT
  docker compose stop polaris >/dev/null 2>&1
  for i in 1 2 3; do gw -o /dev/null "https://polaris.local/actuator/health"; done
  c=$(gw -o /dev/null -w '%{http_code}' "https://polaris.local/actuator/health")
  [ "$c" = 502 ] || [ "$c" = 504 ] && ok "polaris stopped: edge returns $c" || bad "expected 502/504 with polaris stopped, got $c"
  edge_lines | grep '"host":"polaris.local"' | tail -1 | grep -qE '"status":50[234]' && ok "edge stdout log records the 5xx" || bad "no 5xx in edge log"
  err_in_loki() { icurl -s -m 10 -G "$LOKI/loki/api/v1/query_range" --data-urlencode 'query={service_name="nginx-gateway"} | json | status>=500' \
    --data-urlencode "start=$(( $(date +%s) - 300 ))000000000" | grep -q '"values"'; }
  poll "edge 5xx visible in Loki" err_in_loki
  m5xx() { icurl -s -m 10 "$PROM/api/v1/query" --data-urlencode 'query=sum(traces_spanmetrics_calls_total{service="nginx-gateway",http_response_status_code=~"50[234]"})' | grep -q '"value"'; }
  poll "edge 5xx visible in span metrics" m5xx
  docker compose start polaris >/dev/null 2>&1

  docker compose stop otel-collector >/dev/null 2>&1
  bad_n=0; for i in $(seq 1 20); do c=$(gw -o /dev/null -w '%{http_code}' "https://id.polaris.local/realms/master/.well-known/openid-configuration"); [ "$c" = 200 ] || bad_n=$((bad_n+1)); done
  [ $bad_n -eq 0 ] && ok "collector stopped: nginx served 20/20 requests normally" || bad "collector stopped: $bad_n/20 requests failed"
  # OBS-RES-1: the gateway must also (re)start while the Collector is down
  docker compose restart nginx >/dev/null 2>&1
  for i in $(seq 1 30); do [ "$(gw -o /dev/null -w '%{http_code}' https://id.polaris.local/realms/master/.well-known/openid-configuration)" = 200 ] && break; sleep 2; done
  c=$(gw -o /dev/null -w '%{http_code}' "https://id.polaris.local/realms/master/.well-known/openid-configuration")
  [ "$c" = 200 ] && ok "nginx restarted with the collector down and serves HTTP 200" || bad "nginx restart with collector down: got $c"
  docker compose start otel-collector >/dev/null 2>&1
fi

echo; [ $fail -eq 0 ] && echo "PASS" || echo "FAIL"
exit $fail
