#!/usr/bin/env bash
# End-to-end observability verification (plan O8, regression for OBS-*). Needs the stack (make up), docker, curl, python3, ruby.
# Not run in CI (needs the full compose stack). Read-only apart from ONE placed order; nothing is stopped or deleted.
#
# 1. stack      every telemetry/platform container is healthy (UP=1 runs `make up` first, which never deletes volumes)
# 2. order      one order through the gateway (OIDC password grant for the seeded dev shopper, like tests/e2e/run-fulfilment.sh),
#               polled until DELIVERED so the Kafka consumers have run
# 3. trace      Tempo: ONE trace rooted at the nginx-gateway span holding HTTP -> outbox publish -> Kafka produce -> consumer spans
#               (OBS-TRC-1, OBS-EDGE-1)
# 4. logs       Loki: the same trace_id on app logs AND the edge access log (OBS-LOG-1, OBS-DIA-1)
# 5. metrics    Prometheus: span metrics, service graph, exemplars, outbox signals (OBS-MET-1, OBS-ASY-1, OBS-DIA-1)
# 6. dashboards every provisioned dashboard loads through the Grafana API and all its queries run (OBS-VIS-1, OBS-DIA-1)
# 7. forbidden  no forbidden metric label (user_id, order_*), Loki index labels within the allow-list, and neither the bearer token,
#               the password nor the OAuth secrets in the order's trace, its logs or the edge logs (OBS-MET-1, OBS-LOG-1, OBS-SEC-1)
# 8. regression the existing live checks, composed (not duplicated): validate-configs, correlation-check, edge-check,
#               redaction-check, access-check (CHECKS env; SKIP_CHECKS=1 skips them)
# Usage: tests/e2e/observability/run.sh
# Env:   UP=1  WAIT_SECS (90)  E2E_USER/E2E_PASSWORD (alice.tran/testpass)  SKU (E2E-UNLIMITED-01)  DELIVER_SECS (120)
#        RUN_FULFILMENT=1 runs tests/e2e/run-fulfilment.sh first (creates the dev users and SKU in a realm that lacks them)
#        CHECKS="validate-configs correlation-check edge-check redaction-check access-check"  SKIP_CHECKS=1
set -uo pipefail
cd "$(dirname "$0")/../../.."
. tests/e2e/observability/lib.sh
E2E_USER=${E2E_USER:-alice.tran}; E2E_PASSWORD=${E2E_PASSWORD:-testpass}; SKU=${SKU:-E2E-UNLIMITED-01}
DELIVER=${DELIVER_SECS:-120}
CHECKS=${CHECKS:-"validate-configs correlation-check edge-check redaction-check access-check"}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
START=$(date +%s)

echo "== 1. stack"
[ "${UP:-0}" = 1 ] && { ./scripts/init-env.sh && docker compose up -d --build >/dev/null 2>&1 && ok "make up equivalent (docker compose up -d --build)" || bad "compose up"; }
out=$(wait_stack_healthy "$([ "${UP:-0}" = 1 ] && echo 300 || echo 5)") && ok "all $(echo $STACK | wc -w | tr -d ' ') stack containers healthy" || { bad "stack not up ($out); run make up (or UP=1)"; exit 1; }
[ -n "$GRAFANA_AUTH" ] && [ "${GRAFANA_AUTH#*:}" != "" ] && ok "grafana credentials resolved from .env" || { bad "no GRAFANA_ADMIN_PASSWORD in .env (run scripts/init-env.sh)"; exit 1; }

echo "== 2. place an order through the gateway"
[ "${RUN_FULFILMENT:-0}" = 1 ] && { ./tests/e2e/run-fulfilment.sh >/dev/null 2>&1 && ok "run-fulfilment.sh prepared users/SKU" || bad "run-fulfilment.sh"; }
TOK=$(gw -d grant_type=password -d client_id=polaris-app --data-urlencode "username=$E2E_USER" --data-urlencode "password=$E2E_PASSWORD" \
  https://id.polaris.local/realms/polaris/protocol/openid-connect/token | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
[ -n "$TOK" ] || { bad "no OIDC token for $E2E_USER (dev users missing? rerun with RUN_FULFILMENT=1)"; exit 1; }
ok "OIDC token obtained for $E2E_USER"
T_ORDER=$(date +%s)
gw -D "$TMP/hdr" -o "$TMP/body" -X POST https://polaris.local/api/v1/orders -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: o8-$(openssl rand -hex 6)" -d "{\"items\":[{\"sku\":\"$SKU\",\"quantity\":1}]}"
head -1 "$TMP/hdr" | grep -q ' 201' && ok "POST /api/v1/orders -> 201" || { bad "order not created: $(head -1 "$TMP/hdr") $(head -c 300 "$TMP/body")"; exit 1; }
TID=$(tr -d '\r' < "$TMP/hdr" | awk -F': ' 'tolower($1)=="x-trace-id"{print $2}')
ORDER=$(python3 -c "import json;print(json.load(open('$TMP/body'))['orderNumber'])")
[ ${#TID} -eq 32 ] && ok "order $ORDER, response header X-Trace-Id = $TID" || { bad "no X-Trace-Id header"; exit 1; }
delivered() { gw -H "Authorization: Bearer $TOK" "https://polaris.local/api/v1/orders/$ORDER" | grep -q '"status":"DELIVERED"'; }
WAIT=$DELIVER poll "order $ORDER reaches DELIVERED (consumers ran)" delivered
sleep 10   # collector batch + backend ingest

echo "== 3. trace: nginx root -> HTTP -> outbox -> Kafka produce -> consumer"
trace_ok() {
  icurl -sf -m 20 "$TEMPO/api/v2/traces/$TID" | python3 -c '
import sys,json,base64
d=json.load(sys.stdin); t=d.get("trace",d)
def hx(v):
    if not v: return ""
    return v if len(v) in (16,32) and all(c in "0123456789abcdef" for c in v) else base64.b64decode(v).hex()
sp={}
for rs in t["resourceSpans"]:
    svc={a["key"]:list(a["value"].values())[0] for a in rs["resource"]["attributes"]}.get("service.name")
    for ss in rs["scopeSpans"]:
        for s in ss["spans"]: sp[hx(s["spanId"])]=(svc,s,hx(s.get("parentSpanId")))
roots=[i for i,(_,_,p) in sp.items() if not p or p not in sp]
assert len(roots)==1, f"expected one root span, found {len(roots)}"
r=roots[0]; rn=sp[r][1]["name"]
assert sp[r][0]=="nginx-gateway" and sp[r][1]["kind"]=="SPAN_KIND_SERVER", f"root is {sp[r][0]} {rn}, not the nginx span"
def under_root(i):
    seen=set()
    while i in sp and i not in seen:
        if i==r: return True
        seen.add(i); i=sp[i][2]
    return False
def find(kind,pred,what):
    m=[i for i,(svc,s,_) in sp.items() if s["kind"]==kind and pred(s["name"])]
    assert m, f"missing {what}"; assert all(under_root(i) for i in m), f"{what} not descended from the nginx root span"
find("SPAN_KIND_SERVER",lambda n:n=="http post /api/v1/orders","polaris HTTP server span (http post /api/v1/orders)")
find("SPAN_KIND_PRODUCER",lambda n:n.startswith("outbox publish"),"outbox publish span")
find("SPAN_KIND_PRODUCER",lambda n:n.endswith(" send"),"Kafka produce span")
find("SPAN_KIND_CONSUMER",lambda n:n.endswith(" process"),"Kafka consumer span")
print(len(sp),"spans, root",r)'
}
poll "ONE trace $TID: nginx-gateway root -> http post /api/v1/orders -> outbox publish -> Kafka send -> consumer process" trace_ok

echo "== 4. logs carry the same trace_id"
loki_for() { # <logql selector> -> json
  icurl -s -m 20 -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query=$1 | trace_id=\"$TID\"" --data-urlencode limit=500 \
    --data-urlencode "start=$(( T_ORDER - 60 ))000000000" --data-urlencode "end=$(( $(date +%s) + 60 ))000000000"; }
logs_ok() {
  loki_for '{service_name=~".+"}' > "$TMP/loki.json"
  python3 - "$TMP/loki.json" "$TID" <<'PY'
import sys,json
d=json.load(open(sys.argv[1])); tid=sys.argv[2]
svcs=set(); n=0
for s in d["data"]["result"]:
    svcs.add(s["stream"].get("service_name")); n+=len(s["values"])
    for _,line in s["values"]:
        try: j=json.loads(line)
        except Exception: continue
        t=j.get("trace_id") or j.get("traceId")
        assert t in (None,tid), f"line with another trace_id {t}"
assert n>0 and "nginx-gateway" in svcs and "Polaris" in svcs, f"need edge + app logs, got {n} lines from {sorted(map(str,svcs))}"
print(n,"log lines from",sorted(svcs))
PY
}
poll "Loki returns edge (nginx-gateway) and app (Polaris) logs for trace_id $TID" logs_ok
[ -s "$TMP/loki.json" ] && grep -q "$ORDER" "$TMP/loki.json" && ok "an app log in the trace carries the business key $ORDER (logs only, never metrics)" \
  || info "no log line in this trace mentions $ORDER (business key not logged on this path)"

echo "== 5. metrics"
qcount() { icurl -s -m 15 "$PROM/api/v1/query" --data-urlencode "query=count($1)" | grep -q '"value"'; }
span_metrics() { qcount 'traces_spanmetrics_calls_total{service="nginx-gateway"}' && qcount 'traces_spanmetrics_calls_total{service="Polaris"}'; }
poll "span metrics exist for the gateway and polaris" span_metrics
svc_graph() { qcount 'traces_service_graph_request_total{client="user",server="nginx-gateway"}' && qcount 'traces_service_graph_request_total{client="Polaris-Fulfilment-Emulator",server="Polaris"}'; }
poll "service-graph series exist (user -> nginx-gateway, Polaris-Fulfilment-Emulator -> Polaris)" svc_graph
exemplar() { icurl -s -m 15 "$PROM/api/v1/query_exemplars" --data-urlencode 'query=traces_spanmetrics_latency_bucket' \
  --data-urlencode "start=$(( $(date +%s) - 3600 ))" --data-urlencode "end=$(date +%s)" | grep -q '"traceID"'; }
poll "span-metric exemplars carry trace ids (alert -> panel -> trace hop)" exemplar
for m in polaris_outbox_backlog_events polaris_outbox_oldest_pending_age_seconds; do
  poll "outbox signal $m queryable" qcount "$m"
done

echo "== 6. dashboards"
for f in docker/telemetry/grafana/provisioning/dashboards/json/*/*.json; do
  uid=$(python3 -c "import json,sys;print(json.load(open('$f'))['uid'])")
  icurl -s -m 15 -u "$GRAFANA_AUTH" "$GRAFANA/api/dashboards/uid/$uid" | python3 -c '
import sys,json
d=json.load(sys.stdin); assert d["meta"]["provisioned"] and d["dashboard"]["panels"]' 2>/dev/null \
    && ok "dashboard $uid loads via Grafana API (provisioned, has panels)" || bad "dashboard $uid does not load"
done
./scripts/telemetry/validate-dashboards.sh --live > "$TMP/vd.out" 2>&1 && ok "validate-dashboards.sh --live: every panel query runs on Prometheus ($(grep -c '^PASS' "$TMP/vd.out") checks)" \
  || { bad "validate-dashboards.sh --live"; grep '^FAIL' "$TMP/vd.out" | head; }

echo "== 7. forbidden labels and secrets"
icurl -s -m 15 "$PROM/api/v1/labels" | python3 -c '
import sys,json,re
l=json.load(sys.stdin)["data"]; bad=[x for x in l if re.fullmatch(r"(?i)user[._]?id|order[._]?(id|number|no)|orderNumber|customer[._]?(id|email)|email|authorization|password|token", x)]
sys.exit(print("forbidden Prometheus labels:",bad) or 1) if bad else sys.exit(0)' \
  && ok "no forbidden label (user_id, order_*, ...) among $(icurl -s -m 15 "$PROM/api/v1/labels" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]))') Prometheus label names" || bad "forbidden Prometheus label"
icurl -s -m 15 -G "$PROM/api/v1/series" --data-urlencode "match[]={order_number!=\"\"}" --data-urlencode "match[]={orderNumber!=\"\"}" --data-urlencode "match[]={user_id!=\"\"}" \
  --data-urlencode "match[]={order_id!=\"\"}" --data-urlencode "start=$(( $(date +%s) - 3600 ))" | python3 -c '
import sys,json; d=json.load(sys.stdin)["data"]; sys.exit(print("series with forbidden labels:",d[:3]) or 1) if d else sys.exit(0)' \
  && ok "no Prometheus series carries user_id/order_* labels" || bad "series with forbidden labels"
icurl -s -m 15 "$LOKI/loki/api/v1/labels" | python3 -c '
import sys,json
allow={"service_name","service_namespace","deployment_environment","__stream_shard__","service_instance_id","level","detected_level","exporter","job"}
l=set(json.load(sys.stdin)["data"]); extra=l-allow
sys.exit(print("unexpected Loki index labels:",sorted(extra)) or 1) if extra else sys.exit(0)' \
  && ok "Loki index labels are within the allow-list" || bad "unexpected Loki index label"
SIG=${TOK##*.}
secret_free() { # <file> <description>
  if grep -qF -e "$SIG" -e "$E2E_PASSWORD" -e "Bearer $TOK" "$1"; then bad "bearer token / password found in $2"; else ok "no bearer token or password in $2"; fi; }
icurl -sf -m 20 "$TEMPO/api/traces/$TID" > "$TMP/trace.json" && secret_free "$TMP/trace.json" "the order's trace (all spans, raw)" || bad "could not fetch trace for the secret scan"
loki_for '{service_name=~".+"}' > "$TMP/loki2.json"; secret_free "$TMP/loki2.json" "Loki logs of the order's trace"
icurl -s -m 20 -G "$LOKI/loki/api/v1/query_range" --data-urlencode 'query={service_name="nginx-gateway"}' --data-urlencode limit=1000 \
  --data-urlencode "start=$(( T_ORDER - 60 ))000000000" > "$TMP/edge.json"
secret_free "$TMP/edge.json" "the edge access logs in Loki (including the token request to id.polaris.local)"

if [ "${SKIP_CHECKS:-0}" != 1 ]; then
  echo "== 8. composed regression checks"
  for c in $CHECKS; do
    [ -x "scripts/telemetry/$c.sh" ] || { bad "scripts/telemetry/$c.sh missing"; continue; }
    bounded 900 "scripts/telemetry/$c.sh" > "$TMP/$c.out" 2>&1; rc=$?
    if [ $rc -eq 0 ]; then ok "$c.sh ($(grep -c '^PASS' "$TMP/$c.out") PASS lines)"; else bad "$c.sh exit $rc"; grep '^FAIL' "$TMP/$c.out" | head -5; fi
  done
fi

echo; echo "elapsed $(( $(date +%s) - START ))s, order $ORDER, trace $TID"
[ $fail -eq 0 ] && echo "PASS" || echo "FAIL"
exit $fail
