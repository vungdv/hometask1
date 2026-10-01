# Shared helpers for tests/e2e/observability/*.sh (plan O8). Source from the repo root:
#   cd "$(dirname "$0")/../../.."; . tests/e2e/observability/lib.sh
# Backends are reached through scripts/telemetry/lib/net.sh (inside polaris-net; no host ports, O7).
. scripts/telemetry/lib/net.sh
fail=0
ok()   { echo "PASS $1"; }
bad()  { echo "FAIL $1"; fail=1; }
info() { echo "INFO $1"; }
gw()   { curl -sk -m 20 --resolve polaris.local:443:127.0.0.1 --resolve id.polaris.local:443:127.0.0.1 "$@"; }
WAIT=${WAIT_SECS:-90}
poll() { # poll <description> <cmd...> : retry every 3 s until it succeeds or WAIT elapses
  local d=$1; shift; local end=$((SECONDS + WAIT))
  until "$@" >/dev/null 2>&1; do [ $SECONDS -ge $end ] && { bad "$d (waited ${WAIT}s)"; return 1; }; sleep 3; done
  ok "$d"
}
healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = healthy ]; }
# bounded <seconds> <cmd...> : run a command, TERM it after <seconds> (no coreutils `timeout` on macOS). Returns its status, 124 on timeout.
bounded() {
  local s=$1; shift; "$@" & local p=$!
  ( sleep "$s"; kill -TERM "$p" 2>/dev/null; sleep 5; kill -KILL "$p" 2>/dev/null ) >/dev/null 2>&1 & local w=$!
  wait "$p"; local rc=$?; kill "$w" 2>/dev/null; wait "$w" 2>/dev/null
  [ $rc -eq 143 ] && return 124; return $rc
}
# Containers that must be healthy for the stack to count as "up" (compose service names == container names).
STACK="polaris polaris-assistant polaris-fulfilment-emulator nginx keycloak kafka-1 kafka-2 kafka-3 otel-collector tempo loki prometheus alertmanager grafana mailpit alert-webhook"
wait_stack_healthy() { # wait_stack_healthy <seconds>
  local end=$((SECONDS + $1)) c miss
  while :; do
    miss=""; for c in $STACK; do healthy "$c" || miss="$miss $c"; done
    [ -z "$miss" ] && return 0
    [ $SECONDS -ge $end ] && { echo "not healthy:$miss"; return 1; }; sleep 5
  done
}
