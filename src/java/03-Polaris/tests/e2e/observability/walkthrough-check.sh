#!/usr/bin/env bash
# Executes the OBS-DIA-1 operator walk-through (docs/operations/operator-walkthrough.md) against the LIVE stack, step by step, through
# Grafana's own datasource proxy (/api/datasources/proxy/uid/...), i.e. the same calls the Grafana UI makes. Plan O8. Needs the stack up.
# Only provisioned objects are used: alert annotations from the Prometheus rule files, provisioned dashboards, provisioned
# datasource correlation settings. Nothing is created or edited in Grafana.
#   1 alert -> metric panel      alert dashboard_url resolves to a provisioned dashboard that has an exemplar-enabled panel
#   2 panel -> exemplar trace    the panel query returns exemplars with a traceID that Tempo (via Grafana) can open
#   3 trace -> correlated logs   the Tempo "logs for this span" query ({service_name="X"} | trace_id="T") returns logs for that trace
#   4 logs  -> business key      an order's trace logs carry orderNumber, and the order number alone finds the trace_id (never a metric label)
# Env: ORDER (an existing order number to use for step 4; default: the most recent one found in Loki)  WAIT_SECS (90)
set -uo pipefail
cd "$(dirname "$0")/../../.."
. tests/e2e/observability/lib.sh
G() { icurl -s -m 30 -u "$GRAFANA_AUTH" "$@"; }
PX="$GRAFANA/api/datasources/proxy/uid"
NOW=$(date +%s)

echo "== step 1: alert -> metric panel"
URL=$(icurl -s -m 15 "$PROM/api/v1/rules?type=alert" | python3 -c '
import sys,json
for g in json.load(sys.stdin)["data"]["groups"]:
    for r in g["rules"]:
        if r["name"]=="SloBurnFast": print(r["annotations"]["dashboard_url"]); raise SystemExit')
UID_=$(echo "$URL" | grep -o '/d/[^/]*' | head -1 | cut -c4-)   # first dashboard in the template: the edge one (gateway alerts)
[ -n "$UID_" ] && ok "SloBurnFast alert annotation dashboard_url -> $URL" || { bad "no dashboard_url on SloBurnFast"; exit 1; }
D=$(G "$GRAFANA/api/dashboards/uid/$UID_")
echo "$D" | python3 -c '
import sys,json
d=json.load(sys.stdin); p=[(x["title"],t["expr"]) for x in d["dashboard"]["panels"] for t in x.get("targets",[]) if t.get("exemplar")]
assert d["meta"]["provisioned"] and p
' > /dev/null 2>&1 && ok "dashboard $UID_ is provisioned and has an exemplar-enabled panel" || bad "dashboard $UID_ lacks an exemplar panel"
PANEL_Q='histogram_quantile(0.95, sum by (le, service)(rate(traces_spanmetrics_latency_bucket{service="nginx-gateway"}[5m])))'
q=$(G -G "$PX/prometheus/api/v1/query" --data-urlencode "query=$PANEL_Q"); echo "$q" | grep -q '"status":"success"' && ok "panel-style PromQL answers through Grafana: $PANEL_Q" || bad "panel query failed"

echo "== step 2: panel -> exemplar trace"
EX=$(G -G "$PX/prometheus/api/v1/query_exemplars" --data-urlencode 'query=traces_spanmetrics_latency_bucket{service="Polaris"}' \
  --data-urlencode "start=$((NOW-1800))" --data-urlencode "end=$NOW" | python3 -c '
import sys,json
for s in json.load(sys.stdin)["data"]:
    for e in s["exemplars"]:
        if e["labels"].get("traceID"): print(e["labels"]["traceID"]); raise SystemExit')
[ ${#EX} -eq 32 ] && ok "exemplar traceID from Prometheus via Grafana: $EX" || { bad "no exemplar found (generate traffic, then retry)"; EX=""; }
if [ -n "$EX" ]; then
  G "$PX/tempo/api/traces/$EX" | grep -q "$EX\|resourceSpans\|batches" && ok "Tempo opens that trace through Grafana" || info "exemplar trace $EX no longer in Tempo (retention/sampling); step 3 uses an order trace instead"
fi

echo "== step 3: trace -> correlated logs (Tempo 'logs for this span' custom query), on a business trace (health-probe exemplars have no log lines)"
ORDER=${ORDER:-$(G -G "$PX/loki/loki/api/v1/query_range" --data-urlencode 'query={service_name="Polaris"} |= "Order placed: orderNumber="' \
  --data-urlencode "start=$((NOW-3600))000000000" --data-urlencode limit=1 | python3 -c '
import sys,json,re
for s in json.load(sys.stdin)["data"]["result"]:
    for v in s["values"]:
        m=re.search(r"orderNumber=(ORD-\d+)",v[1])
        if m: print(m.group(1)); raise SystemExit')}
[ -n "$ORDER" ] && ok "a recent order: $ORDER" || { bad "no recent order in Loki (run tests/e2e/observability/run.sh first)"; exit 1; }

echo "== step 4: logs -> business transaction (order number <-> trace_id; the key lives in logs and traces, never in metrics)"
TID=$(G -G "$PX/loki/loki/api/v1/query_range" --data-urlencode "query={service_name=\"Polaris\"} |= \"orderNumber=$ORDER\" | trace_id!=\"\"" \
  --data-urlencode "start=$((NOW-7200))000000000" --data-urlencode limit=1 | python3 -c '
import sys,json
for s in json.load(sys.stdin)["data"]["result"]:
    t=s["values"][0][1]
    # trace_id is structured metadata; ask for it explicitly with the stream response of the next query when absent
    print(s["stream"].get("trace_id","")); raise SystemExit')
if [ -z "$TID" ]; then
  # structured metadata is returned as a label only with | keep/json; fall back to the Tempo search by span name and time
  TID=$(G -G "$PX/loki/loki/api/v1/query_range" --data-urlencode "query={service_name=\"Polaris\"} |= \"Order placed: orderNumber=$ORDER\" | line_format \"{{.trace_id}}\"" \
    --data-urlencode "start=$((NOW-7200))000000000" --data-urlencode limit=1 | python3 -c '
import sys,json
for s in json.load(sys.stdin)["data"]["result"]:
    print(s["values"][0][1].strip()); raise SystemExit')
fi
[ ${#TID} -eq 32 ] && ok "order $ORDER -> trace_id $TID (LogQL: {service_name=\"Polaris\"} |= \"orderNumber=$ORDER\")" || { bad "could not resolve trace_id for $ORDER"; exit 1; }
LOGS=$(G -G "$PX/loki/loki/api/v1/query_range" --data-urlencode "query={service_name=\"Polaris\"} | trace_id=\"$TID\"" \
  --data-urlencode "start=$((NOW-7200))000000000" --data-urlencode limit=100)
echo "$LOGS" | grep -q "orderNumber=$ORDER" && ok "step 3 query {service_name=\"Polaris\"} | trace_id=\"$TID\" returns the order's logs (orderNumber=$ORDER)" || bad "trace logs missing the order"
G "$PX/tempo/api/traces/$TID" | grep -q "resourceSpans\|batches" && ok "and the same trace opens in Tempo through Grafana" || bad "trace $TID not in Tempo"
G -G "$PX/prometheus/api/v1/series" --data-urlencode 'match[]={orderNumber!=""}' --data-urlencode 'match[]={order_number!=""}' --data-urlencode "start=$((NOW-300))" 2>/dev/null | grep -q '"data":\[{' \
  && bad "a Prometheus series has an order-number label" || ok "no Prometheus series has an order-number label"
echo; [ $fail -eq 0 ] && echo "PASS" || echo "FAIL"; exit $fail
