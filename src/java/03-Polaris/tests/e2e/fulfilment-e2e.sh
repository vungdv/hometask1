#!/usr/bin/env bash
# F4 end-to-end check (PRD-007 Scenarios 2, 4-7): against the running full stack, place orders through the
# public REST API with an OIDC token and verify that partners claim them first-wins, that every order ends
# DELIVERED with an assigned partner, and that all 5 order.*.v1 CloudEvents reached Kafka in order.
#
# Usage: make e2e-fulfilment          (stack must be up: make up)
# Env:   API_BASE, TOKEN_URL, KC_RESOLVE, E2E_USER, E2E_PASSWORD, SKU, BATCH, TIMEOUT
set -uo pipefail

API_BASE=${API_BASE:-http://localhost:8080}
TOKEN_URL=${TOKEN_URL:-https://id.polaris.local/realms/polaris/protocol/openid-connect/token}
# The token issuer must be the canonical public URL; resolve it to the local nginx without needing /etc/hosts.
KC_RESOLVE=${KC_RESOLVE:-id.polaris.local:443:127.0.0.1}
E2E_USER=${E2E_USER:-alice.tran}
E2E_PASSWORD=${E2E_PASSWORD:-testpass}
SKU=${SKU:-NG-EARBUD-01}
BATCH=${BATCH:-10}
TIMEOUT=${TIMEOUT:-120}            # seconds allowed for the whole fulfilment of all orders
TOPIC=polaris.order.lifecycle
KAFKA_BOOTSTRAP=kafka-1:9092,kafka-2:9092,kafka-3:9092
EVENTS=(placed confirmed parceled delivering delivered)

FAILS=0
step() { printf '\n== %s\n' "$*"; }
ok()   { printf '   ok   %s\n' "$*"; }
bad()  { printf '   FAIL %s\n' "$*"; FAILS=$((FAILS + 1)); }
die()  { printf '   FAIL %s\n' "$*"; printf '\nFAIL\n'; exit 1; }

command -v jq >/dev/null || die "jq is required"

token() {
  curl -sk --resolve "$KC_RESOLVE" -d grant_type=password -d client_id=polaris-app \
    -d "username=$E2E_USER" -d "password=$E2E_PASSWORD" "$TOKEN_URL" | jq -r '.access_token // empty'
}

place_order() { # prints the order number
  curl -s -X POST "$API_BASE/api/v1/orders" -H "Authorization: Bearer $(token)" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: e2e-$(date +%s)-$RANDOM-$1" \
    -d "{\"items\":[{\"sku\":\"$SKU\",\"quantity\":1}]}" | jq -r '.orderNumber // empty'
}

order_json() { curl -s "$API_BASE/api/v1/orders/$1" -H "Authorization: Bearer $2"; }

# Reads the whole lifecycle topic through the cluster's own console consumer (what any consumer would see).
# Output lines: <headers> TAB <key> TAB <value>; prints "<orderNumber> <ce_type suffix>" in topic order.
lifecycle_events() {
  docker compose exec -T kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "$TOPIC" --from-beginning --timeout-ms 6000 --property print.headers=true --property print.key=true 2>/dev/null |
    awk -F'\t' '{ if (match($1, /ce_type:vn\.danang\.polaris\.order\.[a-z]+\.v1/)) { t = substr($1, RSTART, RLENGTH);
      sub(/.*order\./, "", t); sub(/\.v1/, "", t); print $2, t } }'
}

step "Preflight"
TOKEN=$(token)
[ -n "$TOKEN" ] || die "no OIDC token from $TOKEN_URL (is the stack up? make up)"
ok "token for $E2E_USER"
curl -sf "$API_BASE/actuator/health/readiness" >/dev/null || die "polaris not ready at $API_BASE"
ok "polaris ready"
docker compose ps --status running --format '{{.Name}}' | grep -qx polaris-fulfilment-emulator ||
  die "polaris-fulfilment-emulator is not running (make up)"
ok "emulator running"

step "Place 1 order + $BATCH-order batch"
SINGLE=$(place_order single)
[ -n "$SINGLE" ] && ok "single order $SINGLE" || die "could not place the single order"
BATCH_ORDERS=()
for i in $(seq 1 "$BATCH"); do
  n=$(place_order "b$i")
  [ -n "$n" ] && BATCH_ORDERS+=("$n") || bad "batch order $i was not placed"
done
ok "${#BATCH_ORDERS[@]} of $BATCH batch orders placed"
ALL=("$SINGLE" "${BATCH_ORDERS[@]}")

step "Wait for DELIVERED (timeout ${TIMEOUT}s)"
deadline=$((SECONDS + TIMEOUT))
declare -A STATUS PARTNER
while :; do
  pending=0
  TOKEN=$(token)
  for n in "${ALL[@]}"; do
    [ "${STATUS[$n]:-}" = DELIVERED ] && continue
    j=$(order_json "$n" "$TOKEN")
    STATUS[$n]=$(jq -r '.status // "?"' <<<"$j"); PARTNER[$n]=$(jq -r '.assignedPartner // empty' <<<"$j")
    [ "${STATUS[$n]}" = DELIVERED ] || pending=$((pending + 1))
  done
  [ "$pending" -eq 0 ] && break
  [ "$SECONDS" -ge "$deadline" ] && break
  sleep 2
done
for n in "${ALL[@]}"; do
  if [ "${STATUS[$n]}" = DELIVERED ] && [ -n "${PARTNER[$n]}" ]; then ok "$n DELIVERED, partner ${PARTNER[$n]}"
  else bad "$n is ${STATUS[$n]:-?} (partner '${PARTNER[$n]:-}') after ${TIMEOUT}s"; fi
done

step "Kafka: 5 order.*.v1 events per order, in order"
expected=$(printf '%s ' "${EVENTS[@]}")
events_ok=0
for attempt in 1 2 3 4 5; do
  captured=$(lifecycle_events)
  [ -n "$captured" ] || { sleep 2; continue; }
  events_ok=1
  for n in "${ALL[@]}"; do
    got=$(awk -v n="$n" '$1 == n { printf "%s ", $2 }' <<<"$captured")
    [ "$got" = "$expected" ] || { events_ok=0; break; }
  done
  [ "$events_ok" -eq 1 ] && break
  sleep 3
done
for n in "${ALL[@]}"; do
  got=$(awk -v n="$n" '$1 == n { printf "%s ", $2 }' <<<"${captured:-}")
  if [ "$got" = "$expected" ]; then ok "$n: ${got% }"; else bad "$n: got '${got% }', want '${expected% }'"; fi
done

step "Batch: more than one winning partner"
winners=$(for n in "${BATCH_ORDERS[@]}"; do echo "${PARTNER[$n]:-}"; done | grep -v '^$' | sort | uniq -c)
echo "$winners" | sed 's/^/   /'
count=$(printf '%s\n' "$winners" | grep -c .)
[ "$count" -gt 1 ] && ok "$count distinct winning partners" || bad "only $count distinct winning partner(s)"

echo
if [ "$FAILS" -eq 0 ]; then echo "PASS: ${#ALL[@]} orders DELIVERED with 5 events each"; else echo "FAIL: $FAILS check(s) failed"; exit 1; fi
