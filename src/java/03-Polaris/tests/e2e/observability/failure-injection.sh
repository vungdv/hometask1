#!/usr/bin/env bash
# Failure injection and recovery (plan O8). Needs the stack up (make up). Not run in CI. DISRUPTIVE: stops tempo, loki, the collector,
# nginx, polaris and the Kafka brokers in turn. It never deletes a volume, and an EXIT trap starts everything it may have stopped
# and waits for health, even on Ctrl-C or a failed phase.
#
# Composes the existing live scripts, each under a hard time bound (BOUND_* below), instead of re-implementing them:
#   resilience  O1 / OBS-RES-1   scripts/telemetry/resilience-check.sh   business status 200 and bounded latency while tempo, loki and the
#                                collector are stopped under load; telemetry resumes without restarting anything else
#   alerts      O5 / OBS-SLO-1,  scripts/telemetry/alerts-check.sh       burn (polaris stopped), backend (loki), gateway (nginx) and collector
#               OBS-PIPE-1       scenarios: alert firing in Alertmanager, email in Mailpit, webhook for page severity
#   async       O6 / OBS-ASY-1   scripts/telemetry/async-check.sh        Kafka stopped: orders still accepted, outbox pending count and age rise,
#                                OutboxStuck (test-window copy) fires, email in Mailpit, metrics keep exporting, drain on recovery
#   recovery    every container healthy again, no test alert left, and a freshly placed order still gives a complete trace
#               (tests/e2e/observability/run.sh with SKIP_CHECKS=1)
# Usage: tests/e2e/observability/failure-injection.sh
# Env:   STEPS="resilience alerts async"  SCENARIOS (passed to alerts-check, default all four)  PHASE_SECS (20, resilience)
#        BOUND_RESILIENCE (900)  BOUND_ALERTS (3600)  BOUND_ASYNC (1500)  BOUND_RECOVERY (600)  RESTORE_SECS (300)
set -uo pipefail
cd "$(dirname "$0")/../../.."
. tests/e2e/observability/lib.sh
STEPS=${STEPS:-"resilience alerts async"}
MAY_STOP="kafka-1 kafka-2 kafka-3 polaris polaris-assistant polaris-fulfilment-emulator nginx otel-collector tempo loki prometheus alertmanager grafana"
RESTORE=${RESTORE_SECS:-300}

restore() {
  trap - EXIT INT TERM
  echo "== restoring the stack (trap)"
  rm -f docker/telemetry/prometheus/rules-test/test-*.yml; docker kill -s HUP prometheus >/dev/null 2>&1
  for c in $MAY_STOP; do docker start "$c" >/dev/null 2>&1; done
  out=$(wait_stack_healthy "$RESTORE") && ok "restored: all stack containers healthy" || bad "restore: $out"
  echo; [ $fail -eq 0 ] && echo "PASS" || echo "FAIL"; exit $fail
}
trap restore EXIT; trap 'exit 130' INT TERM

step() { # step <name> <bound seconds> <cmd...>
  local n=$1 b=$2; shift 2; echo "== $n (bound ${b}s)"
  local t0=$SECONDS; bounded "$b" "$@"; local rc=$?
  case $rc in 0) ok "$n passed in $((SECONDS - t0))s";; 124) bad "$n exceeded its ${b}s bound and was terminated";; *) bad "$n failed (exit $rc)";; esac
  # a bounded or failed step may leave a container stopped; start before the next step
  for c in $MAY_STOP; do docker start "$c" >/dev/null 2>&1; done
  out=$(wait_stack_healthy "$RESTORE") && ok "stack healthy after $n" || bad "stack not healthy after $n: $out"
}

out=$(wait_stack_healthy 5) || { bad "stack not healthy at start ($out); run make up"; exit 1; }
ok "stack healthy at start"
gw -o /dev/null -w '%{http_code}\n' https://polaris.local/actuator/health | grep -q 200 && ok "business baseline: gateway request returns 200" || bad "gateway baseline request failed"

for s in $STEPS; do
  case $s in
    resilience) step "O1 resilience (OBS-RES-1)" "${BOUND_RESILIENCE:-900}" env PHASE_SECS="${PHASE_SECS:-20}" ./scripts/telemetry/resilience-check.sh ;;
    alerts)     step "O5 alerts and email (OBS-SLO-1, OBS-PIPE-1)" "${BOUND_ALERTS:-3600}" ./scripts/telemetry/alerts-check.sh ;;
    async)      step "O6 outbox and Kafka alert (OBS-ASY-1)" "${BOUND_ASYNC:-1500}" ./scripts/telemetry/async-check.sh ;;
    *) bad "unknown step $s" ;;
  esac
done

echo "== recovery"
no_test_alerts() { icurl -s "$AM/api/v2/alerts?active=true" | python3 -c '
import sys,json
sys.exit(1 if any(x["labels"].get("test")=="true" for x in json.load(sys.stdin)) else 0)'; }
WAIT=${BOUND_RECOVERY:-600} poll "no test-only alert left active in Alertmanager" no_test_alerts
[ ! -e docker/telemetry/prometheus/rules-test/test-short-windows.yml ] && [ ! -e docker/telemetry/prometheus/rules-test/test-async-short.yml ] \
  && ok "test-only rule files removed" || bad "test-only rule file left behind"
step "recovery: placed order is fully traced again" "${BOUND_RECOVERY:-600}" env SKIP_CHECKS=1 ./tests/e2e/observability/run.sh
exit $fail
