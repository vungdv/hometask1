#!/usr/bin/env bash
# Verifies the plan acceptance "all 5 order.*.v1 events on Kafka, in order" for the given order numbers by reading
# polaris.order.lifecycle through the cluster's own console consumer (what any consumer would see).
# Usage: verify-kafka-events.sh ORD-1 ORD-2 ...        (run from the compose project directory)
set -uo pipefail
TOPIC=${TOPIC:-polaris.order.lifecycle}
KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP:-kafka-1:9092,kafka-2:9092,kafka-3:9092}
EXPECTED="placed confirmed parceled delivering delivered"
[ "$#" -gt 0 ] || { echo "FAIL no order numbers given"; exit 1; }

# Output lines: <headers> TAB <key> TAB <value>; prints "<orderNumber> <event>" in topic order.
lifecycle_events() {
  docker compose exec -T kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "$TOPIC" --from-beginning --timeout-ms 6000 --property print.headers=true --property print.key=true 2>/dev/null |
    awk -F'\t' '{ if (match($1, /ce_type:vn\.danang\.polaris\.order\.[a-z]+\.v1/)) { t = substr($1, RSTART, RLENGTH);
      sub(/.*order\./, "", t); sub(/\.v1/, "", t); print $2, t } }'
}

fails=0
for attempt in 1 2 3 4 5; do   # events are published asynchronously; allow the last ones to land
  captured=$(lifecycle_events)
  fails=0
  for n in "$@"; do
    got=$(awk -v n="$n" '$1 == n { printf "%s ", $2 }' <<<"$captured")
    [ "${got% }" = "$EXPECTED" ] || fails=$((fails + 1))
  done
  [ "$fails" -eq 0 ] && break
  sleep 3
done
for n in "$@"; do
  got=$(awk -v n="$n" '$1 == n { printf "%s ", $2 }' <<<"$captured")
  if [ "${got% }" = "$EXPECTED" ]; then echo "   ok   $n: ${got% }"; else echo "   FAIL $n: got '${got% }', want '$EXPECTED'"; fi
done
[ "$fails" -eq 0 ] && echo "Kafka: PASS ($# orders x 5 events)" || { echo "Kafka: FAIL ($fails order(s))"; exit 1; }
