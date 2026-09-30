#!/usr/bin/env bash
# OBS-LOG-1 / OBS-MET-1 verification (plan O3). Needs the stack up (make up), curl, python3 and docker.
# Sends known secrets, PII-shaped values and high-cardinality attributes through the Collector
# (OTLP/HTTP for all three signals, plus a syslog line for the edge access-log pipeline) and
# asserts: secrets/PII are ABSENT from Loki, Tempo and Prometheus; user_id/order_id are absent
# from metric labels but kept on traces and logs; only allow-listed labels index Loki streams.
# Usage: scripts/telemetry/redaction-check.sh
# Env:   OTLP (http://localhost:4318) PROM (http://localhost:9090) TEMPO (http://localhost:3200)
#        LOKI (http://localhost:3100) WAIT_SECS (60)
set -uo pipefail
cd "$(dirname "$0")/../.."
OTLP=${OTLP:-http://localhost:4318}; PROM=${PROM:-http://localhost:9090}
TEMPO=${TEMPO:-http://localhost:3200}; LOKI=${LOKI:-http://localhost:3100}
WAIT=${WAIT_SECS:-60}
fail=0
ok()  { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
poll() { local d=$1; shift; local end=$((SECONDS + WAIT))
  until "$@" >/dev/null 2>&1; do [ $SECONDS -ge $end ] && { bad "$d"; return 1; }; sleep 3; done; ok "$d"; }

RUN=$(openssl rand -hex 6)
SVC="redaction-check-$RUN"; EDGE="edgeprobe$RUN"
TID=$(openssl rand -hex 16); SID=$(openssl rand -hex 8)
NOW=$(python3 -c 'import time;print(int(time.time()*1e9))')
# Secrets and PII-shaped values; every one embeds $RUN so absence is unambiguous.
BEARER="Bearer tok${RUN}AbCdEfGhIj"; COOKIE="JSESSIONID=ck${RUN}"; PASSWORD="pw${RUN}"
EMAIL="alice.${RUN}@example.com"; CARD1="4111 1111 1111 1111"; CARD2="4111111111111111"
JWT="eyJhbGc${RUN}.eyJzdWIi${RUN}.sig${RUN}"
UID_="uid-$RUN"; OID="ord-$RUN"
SECRETS=("tok${RUN}AbCdEfGhIj" "ck${RUN}" "$PASSWORD" "alice.${RUN}@example.com" "4111 1111 1111 1111" "4111111111111111" "eyJhbGc${RUN}" "sig${RUN}")

OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
export OUT RUN SVC TID SID NOW BEARER COOKIE PASSWORD EMAIL CARD1 CARD2 JWT UID_ OID
python3 - <<'PY'
import json, os
e = os.environ
def kv(k, v): return {"key": k, "value": {"stringValue": v}}
res = {"attributes": [kv("service.name", e["SVC"]), kv("deployment.environment", "test")]}
attrs = [kv("http.request.header.authorization", e["BEARER"]), kv("http.request.header.cookie", e["COOKIE"]),
         kv("db.password", e["PASSWORD"]), kv("note.email", "contact " + e["EMAIL"]),
         kv("note.card", "paid with " + e["CARD1"] + " / " + e["CARD2"]), kv("note.jwt", e["JWT"]),
         kv("user_id", e["UID_"]), kv("order_id", e["OID"]), kv("http.route", "/redaction-check")]
t0 = int(e["NOW"])
trace = {"resourceSpans": [{"resource": res, "scopeSpans": [{"spans": [{
    "traceId": e["TID"], "spanId": e["SID"], "name": "redaction probe " + e["EMAIL"], "kind": 2,
    "startTimeUnixNano": str(t0), "endTimeUnixNano": str(t0 + 1000000), "attributes": attrs,
    "status": {"code": 2, "message": "failed for " + e["EMAIL"]},
    "events": [{"timeUnixNano": str(t0), "name": "exception", "attributes": [kv("exception.message", "bad login " + e["EMAIL"] + " " + e["BEARER"])]}]}]}]}]}
logs = {"resourceLogs": [{"resource": res, "scopeLogs": [{"logRecords": [{
    "timeUnixNano": str(t0), "severityNumber": 9, "severityText": "INFO", "traceId": e["TID"], "spanId": e["SID"],
    "body": {"stringValue": "login by " + e["EMAIL"] + " card " + e["CARD1"] + " " + e["BEARER"] + " jwt " + e["JWT"] + " password=" + e["PASSWORD"] + " Cookie: " + e["COOKIE"]},
    "attributes": attrs}]}]}]}
metrics = {"resourceMetrics": [{"resource": res, "scopeMetrics": [{"metrics": [{
    "name": "redaction_check_requests", "sum": {"aggregationTemporality": 2, "isMonotonic": True, "dataPoints": [{
        "timeUnixNano": str(t0), "startTimeUnixNano": str(t0 - 1000000000), "asInt": "1",
        "attributes": attrs + [kv("session.id", e["UID_"])]}]}}]}]}]}
d = e["OUT"]
for n, v in (("traces", trace), ("logs", logs), ("metrics", metrics)):
    json.dump(v, open(f"{d}/rc-{n}.json", "w"))
PY
send() { curl -s -o /dev/null -w '%{http_code}' -m 10 -H 'Content-Type: application/json' --data-binary "@$OUT/rc-$1.json" "$OTLP/v1/$1"; }
for s in traces logs metrics; do
  [ "$(send $s)" = 200 ] && ok "OTLP $s accepted by collector" || bad "OTLP $s rejected"
done

# Edge access-log pipeline: inject a syslog line (as nginx sends it) carrying secrets/PII.
EDGELINE=$(python3 -c '
import json,os
print(json.dumps({"trace_id":"","span_id":"","route":"/x","status":200,"uri":"/orders?email='"$EDGE"'.x@example.com","authorization":"'"$BEARER"'","cookie":"'"$COOKIE"'","card":"'"$CARD1"'"}))')
docker exec nginx sh -c "printf '<190>%s nginx: %s' \"\$(date '+%b %e %H:%M:%S')\" '$EDGELINE' | nc -u -w1 172.29.250.10 5514" >/dev/null 2>&1 \
  && ok "edge syslog line injected" || bad "could not inject edge syslog line (needs the nginx container)"

# ---- Loki ----
lq() { curl -s -m 15 -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query=$1" --data-urlencode "limit=100" \
        --data-urlencode "start=$(( $(date +%s) - 900 ))000000000"; }
found_app() { lq "{service_name=\"$SVC\"}" | grep -q '"values"'; }
poll "loki has the probe log (positive control)" found_app
# the email is redacted, so search by the route prefix
found_edge() { lq '{service_name="nginx-gateway"} |= "/orders?email="' | grep -q "$EDGE\|\*\*\*"; }
poll "loki has the edge probe line (positive control)" found_edge
ALLLOKI="$(lq "{service_name=\"$SVC\"}")$(lq '{service_name="nginx-gateway"} |= "/orders?email="')"
leak=0
for s in "${SECRETS[@]}" "$EDGE.x@example.com"; do
  echo "$ALLLOKI" | grep -qF "$s" && { bad "loki leaks: $s"; leak=1; }
done
[ $leak = 0 ] && ok "no secret/PII value in Loki (app + edge logs)"
echo "$ALLLOKI" | grep -qF "$UID_" && ok "logs keep user_id (structured metadata)" || bad "logs lost user_id"
echo "$ALLLOKI" | grep -q '\*\*\*' && ok "loki shows masked placeholders" || bad "no masked placeholder in loki"
labels=$(curl -s -m 10 "$LOKI/loki/api/v1/labels" --data-urlencode "start=$(( $(date +%s) - 3600 ))000000000" -G)
python3 - "$labels" <<'PY' && ok "loki index labels limited to service_name/service_namespace/deployment_environment" || bad "unexpected loki index labels"
import sys, json
l = set(json.loads(sys.argv[1])["data"]) - {"__stream_shard__"}
sys.exit(0 if l <= {"service_name", "service_namespace", "deployment_environment"} else (print(l), 1)[1])
PY

# ---- Tempo ----
tempo_trace() { curl -sf -m 10 "$TEMPO/api/traces/$TID" -o $OUT/rc-tempo.json; }
poll "tempo has the probe trace (positive control)" tempo_trace
leak=0
for s in "${SECRETS[@]}"; do grep -qF "$s" $OUT/rc-tempo.json && { bad "tempo leaks: $s"; leak=1; }; done
[ $leak = 0 ] && ok "no secret/PII value in Tempo (attributes, span name, status, events)"
grep -qF "$UID_" $OUT/rc-tempo.json && grep -qF "$OID" $OUT/rc-tempo.json && ok "traces keep user_id and order_id" || bad "traces lost user_id/order_id"

# ---- Prometheus ----
series() { curl -s -m 10 -G "$PROM/api/v1/series" --data-urlencode 'match[]={__name__="redaction_check_requests_total",exported_job="'"$SVC"'"}'; }
prom_has() { series | grep -q "$SVC"; }
poll "prometheus has the probe metric (positive control)" prom_has
S=$(series)
leak=0
for s in "${SECRETS[@]}" "$UID_" "$OID"; do echo "$S" | grep -qF "$s" && { bad "prometheus leaks: $s"; leak=1; }; done
[ $leak = 0 ] && ok "no secret/PII/high-cardinality value in Prometheus series"
echo "$S" | grep -qE '"(user_id|order_id|session_id|session\.id)"' && bad "prometheus has user_id/order_id/session labels" || ok "user_id / order_id / session id dropped from metric labels"
echo "$S" | grep -qiE 'authorization|cookie|password' && bad "credential-named metric labels present" || ok "credential-named attributes dropped from metric labels"
echo "$S" | grep -q '"http_route"' && ok "allow-listed metric attribute http_route kept" || bad "allow-listed http_route missing"
exit $fail
