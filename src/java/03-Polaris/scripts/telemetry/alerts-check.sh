#!/usr/bin/env bash
# LIVE failure-injection check for O5 alerting (needs the stack up: make up). Not run in CI.
#
# Real burn-rate windows are 1h..3d, so a live run cannot exercise them (promtool unit tests do).
# This script generates a clearly labelled TEST-ONLY copy of the burn rules with the windows shortened to
# 60s..240s (alert names get a Test prefix and _ShortWindow suffix, label test="true"), loads it via
# docker/telemetry/prometheus/rules-test/ (git-ignored), and removes it again on exit. The pipeline alerts are
# the REAL rules with their real `for:` durations.
#
# Scenarios (SCENARIOS env, default "burn backend gateway collector"):
#   burn       stop polaris under load -> edge 502/504 (availability and >1s latency at the edge)
#   backend    stop loki      -> TelemetryBackendDown
#   gateway    stop nginx     -> GatewayDown
#   collector  stop collector -> TelemetryCollectorDown (and GatewayDown must be inhibited)
# Each scenario asserts: alert firing in Alertmanager, email in Mailpit, and (page severity) a webhook request.
# Env: PROM AM MAILPIT (internal addresses via scripts/telemetry/lib/net.sh), WAIT_SECS (per alert, default 420)
set -uo pipefail
cd "$(dirname "$0")/../.."
. scripts/telemetry/lib/net.sh
WAIT=${WAIT_SECS:-420}; SCENARIOS=${SCENARIOS:-"burn backend gateway collector"}
TESTRULES=docker/telemetry/prometheus/rules-test/test-short-windows.yml
fail=0; LOAD_PID=""
ok()  { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }
gw() { curl -sk -m 10 --resolve polaris.local:443:127.0.0.1 "$@"; }

cleanup() {
  [ -n "$LOAD_PID" ] && kill "$LOAD_PID" 2>/dev/null
  rm -f "$TESTRULES"; docker kill -s HUP prometheus >/dev/null 2>&1
  for c in polaris loki nginx otel-collector; do docker start "$c" >/dev/null 2>&1; done
}
trap cleanup EXIT

start_load() { ( while true; do gw -o /dev/null https://polaris.local/actuator/health; sleep 0.3; done ) & LOAD_PID=$!; }
stop_load()  { [ -n "$LOAD_PID" ] && kill "$LOAD_PID" 2>/dev/null; LOAD_PID=""; }
firing() { # firing <alertname> [service]
  icurl -s "$AM/api/v2/alerts?active=true&silenced=false&inhibited=false" | ALERT=$1 SVC=${2:-} python3 -c '
import sys,json,os
a=[x for x in json.load(sys.stdin) if x["labels"]["alertname"]==os.environ["ALERT"] and (not os.environ["SVC"] or x["labels"].get("service")==os.environ["SVC"])]
sys.exit(0 if a else 1)'; }
inhibited() { icurl -s "$AM/api/v2/alerts?active=true&inhibited=true" | ALERT=$1 python3 -c '
import sys,json,os
sys.exit(0 if any(x["labels"]["alertname"]==os.environ["ALERT"] and x["status"]["inhibitedBy"] for x in json.load(sys.stdin)) else 1)'; }
mail() { icurl -s "$MAILPIT/api/v1/messages?limit=200" | ALERT=$1 python3 -c '
import sys,json,os
sys.exit(0 if any(os.environ["ALERT"] in m["Subject"] for m in json.load(sys.stdin)["messages"]) else 1)'; }
webhook() { [ "$(docker logs alert-webhook 2>&1 | grep -c "alertname[^,]*$1")" -gt 0 ]; }  # not grep -q: SIGPIPE + pipefail
wait_for() { local d=$1; shift; local end=$((SECONDS + WAIT))
  until "$@" >/dev/null 2>&1; do [ $SECONDS -ge $end ] && { bad "$d (waited ${WAIT}s)"; return 1; }; sleep 5; done
  ok "$d after $((WAIT - (end - SECONDS)))s"; }
verify_delivery() { # verify_delivery <alertname> <expect webhook: yes|no> [service]
  wait_for "$1 firing in Alertmanager" firing "$1" ${3:-} || return
  wait_for "email for $1 in Mailpit" mail "$1"
  if [ "$2" = yes ]; then wait_for "webhook request for $1 received" webhook "$1"; fi
}
healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = healthy ]; }

# ---- preconditions
for c in polaris loki nginx otel-collector; do docker start "$c" >/dev/null 2>&1; done
[ "$(gw -o /dev/null -w '%{http_code}' https://polaris.local/actuator/health)" != 000 ] && ok "gateway reachable" || { bad "gateway https://polaris.local not reachable"; exit 1; }
icurl -sf "$PROM/-/ready" >/dev/null && icurl -sf "$AM/-/ready" >/dev/null && icurl -sf "$MAILPIT/api/v1/info" >/dev/null \
  && ok "prometheus, alertmanager, mailpit ready" || { bad "stack not ready"; exit 1; }
icurl -s "$PROM/api/v1/rules" | python3 -c '
import sys,json
g=json.load(sys.stdin)["data"]["groups"]; r=[x for grp in g for x in grp["rules"]]
assert r and all(x["health"]=="ok" for x in r); print(len(r),"rules loaded, all healthy")' && ok "rules loaded and healthy" || bad "rules not loaded/healthy"
icurl -s "$PROM/api/v1/alertmanagers" | grep -q 'alertmanager:9093' && ok "prometheus is wired to alertmanager" || bad "prometheus not wired to alertmanager"
# alerts from an earlier run linger in Alertmanager until they expire; wait so results are not stale
no_test_alerts() { icurl -s "$AM/api/v2/alerts?active=true" | python3 -c '
import sys,json
sys.exit(1 if any(x["labels"].get("test")=="true" for x in json.load(sys.stdin)) else 0)'; }
wait_for "no lingering test alerts from an earlier run" no_test_alerts
icurl -s -X DELETE "$MAILPIT/api/v1/messages" >/dev/null

# ---- test-only short-window copy of the burn rules
ruby -ryaml -e '
map = {"5m"=>"60s","30m"=>"90s","1h"=>"120s","2h"=>"150s","6h"=>"180s","1d"=>"210s","3d"=>"240s"}
out = %w[docker/telemetry/prometheus/rules/sli-windows.rules.yml docker/telemetry/prometheus/rules/slo-burn.rules.yml].map do |f|
  File.read(f).gsub(/(window[=:] ?")(\w+)(")/) { "#{$1}#{map.fetch($2)}#{$3}" }
    .gsub(/count_over_time\(sli:requests:rate5m\[(\w+)\]\) >= \d+/) { "count_over_time(sli:requests:rate5m[#{map.fetch($1)}]) >= #{(map.fetch($1).to_i * 0.8 / 30).floor}" }
    .gsub(/\[(30m|1h|2h|6h|1d|3d)\]/) { "[#{map.fetch($1)}]" }
    .gsub(/^(\s*- alert: )(\w+)/) { "#{$1}Test#{$2}_ShortWindow" }
    .gsub(/^(\s*- name: )(\S+)/) { "#{$1}test-#{$2}" }
    .gsub("        labels:\n          severity:", "        labels:\n          test: \"true\"\n          severity:")
end
groups = out.flat_map { |t| YAML.load(t)["groups"] }
File.write(ARGV[0], "# TEST-ONLY, GENERATED by scripts/telemetry/alerts-check.sh (shortened windows). Do not commit.\n" + {"groups" => groups}.to_yaml)
' "$TESTRULES" && docker run --rm --entrypoint promtool -v "$PWD/docker/telemetry/prometheus/rules-test:/r:ro" prom/prometheus:v3.14.0 check rules /r/test-short-windows.yml >/dev/null \
  && ok "generated short-window test rules pass promtool" || { bad "generated test rules invalid"; exit 1; }
docker kill -s HUP prometheus >/dev/null; sleep 5

for s in $SCENARIOS; do
  echo "== scenario: $s"
  case $s in
    burn)
      start_load; sleep 60          # healthy baseline through the gateway
      firing TestSloBurnFast_ShortWindow && bad "burn alert firing before the failure" || ok "no burn alert on healthy traffic"
      docker stop polaris >/dev/null; T0=$SECONDS
      verify_delivery TestSloBurnFast_ShortWindow yes nginx-gateway
      firing TestSloBurnFast_ShortWindow nginx-gateway && icurl -s "$AM/api/v2/alerts?active=true" | grep -q '"sli":"latency"' \
        && ok "latency SLI burn also visible (edge 504 after the 3s connect timeout is slower than 1.024s)" || echo "INFO latency burn alert not (yet) seen"
      docker start polaris >/dev/null; stop_load; wait_for "polaris healthy again" healthy polaris ;;
    backend)
      docker stop loki >/dev/null; verify_delivery TelemetryBackendDown no; docker start loki >/dev/null ;;
    gateway)
      docker stop nginx >/dev/null; verify_delivery GatewayDown yes nginx-gateway; docker start nginx >/dev/null ;;
    collector)
      docker stop otel-collector >/dev/null; verify_delivery TelemetryCollectorDown yes
      if firing GatewayDown; then bad "GatewayDown is delivered while the collector is down (should be inhibited)"; else ok "GatewayDown not delivered while the collector is down (inhibited or not yet firing)"; fi
      docker start otel-collector >/dev/null ;;
  esac
done
exit $fail
