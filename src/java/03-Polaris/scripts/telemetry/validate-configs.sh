#!/usr/bin/env bash
# Validates telemetry configs with the pinned images (plan O1). Needs docker only.
set -euo pipefail
cd "$(dirname "$0")/../.."
T=docker/telemetry
OTEL_IMG=$(grep -o 'otel/opentelemetry-collector-contrib:[0-9.]*' docker-compose.override.yml | head -1)
PROM_IMG=$(grep -o 'prom/prometheus:v[0-9.]*' docker-compose.override.yml | head -1)
fail=0
ok() { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }

for f in docker-compose.override.yml; do
  ! grep -E 'image:.*:latest|image: *[^:]+$' $f | grep -E 'prometheus|loki|tempo|grafana|otel' >/dev/null && ok "no unpinned telemetry image" || bad "unpinned telemetry image"
done
docker compose -f docker-compose.yml -f docker-compose.override.yml config -q && ok "docker compose config" || bad "docker compose config"

docker run --rm -v "$PWD/$T/otel-collector-config.yaml:/c.yaml:ro" "$OTEL_IMG" validate --config=/c.yaml >/dev/null \
  && ok "otelcol validate ($OTEL_IMG)" || bad "otelcol validate"

docker run --rm --entrypoint promtool -v "$PWD/$T/prometheus/prometheus.yml:/p.yml:ro" "$PROM_IMG" check config /p.yml >/dev/null \
  && ok "promtool check config ($PROM_IMG)" || bad "promtool check config"

ruby -ryaml -e '
c = YAML.load_file(ARGV[0])
def req(cond, msg); abort(msg) unless cond; end
req !c["exporters"].key?("debug") && !c["processors"].key?("attributes"), "debug/attributes present"
c["service"]["pipelines"].each do |name, p|
  req p["processors"][0] == "memory_limiter", "#{name}: memory_limiter not first"
  req !p["exporters"].include?("debug"), "#{name}: debug exporter"
  p["exporters"].each do |e|
    next if e.start_with?("prometheus") # pull-based exporter, no queue
    ex = c["exporters"][e]
    q = ex["sending_queue"] || {}
    req q["enabled"] && q["queue_size"].to_i.between?(1, 10000), "#{e}: sending_queue unbounded/missing"
    req (ex["retry_on_failure"] || {})["enabled"], "#{e}: retry_on_failure missing"
  end
end
req c["service"]["telemetry"]["metrics"]["readers"][0]["pull"]["exporter"]["prometheus"]["port"] == 8888, "self-metrics not on 8888"
' "$T/otel-collector-config.yaml" && ok "collector policy (memory_limiter first, bounded queues, retry, no debug/attributes)" || bad "collector policy"
exit $fail
