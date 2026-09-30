#!/usr/bin/env bash
# Validates telemetry configs with the pinned images (plan O1, O2). Needs docker only.
set -euo pipefail
cd "$(dirname "$0")/../.."
T=docker/telemetry
OTEL_IMG=$(grep -o 'otel/opentelemetry-collector-contrib:[0-9.]*' docker-compose.override.yml | head -1)
PROM_IMG=$(grep -o 'prom/prometheus:v[0-9.]*' docker-compose.override.yml | head -1)
TEMPO_IMG=$(grep -o 'BASE: grafana/tempo:[0-9.]*' docker-compose.override.yml | head -1 | sed 's/BASE: //')
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

# Tempo: strict parse of the pinned image (unknown keys such as a misspelt processor fail)
docker run --rm -v "$PWD/$T/tempo/tempo.yml:/c.yml:ro" "$TEMPO_IMG" -config.file=/c.yml -config.verify=true >/dev/null 2>&1 \
  && ok "tempo -config.verify ($TEMPO_IMG)" || bad "tempo -config.verify"

# Prometheus must accept Tempo's remote write and keep exemplars (plan O2)
grep -q -- '--web.enable-remote-write-receiver' docker-compose.override.yml && grep -q -- '--enable-feature=exemplar-storage' docker-compose.override.yml \
  && ok "prometheus remote-write receiver + exemplar storage enabled" || bad "prometheus remote-write receiver / exemplar storage"

ruby -ryaml -e '
t = YAML.load_file(ARGV[0]); g = t["metrics_generator"]
abort "no remote_write to prometheus" unless g["storage"]["remote_write"].any? { |r| r["url"] == "http://prometheus:9090/api/v1/write" && r["send_exemplars"] }
procs = t["overrides"]["defaults"]["metrics_generator"]["processors"]
abort "span-metrics/service-graphs not enabled" unless (%w[span-metrics service-graphs] - procs).empty?
d = YAML.load_file(ARGV[1])["datasources"].to_h { |x| [x["uid"], x["jsonData"] || {}] }
j = d.fetch("tempo")
abort "tempo->loki" unless j.dig("tracesToLogsV2", "datasourceUid") == "loki"
abort "tempo->prometheus" unless j.dig("tracesToMetrics", "datasourceUid") == "prometheus"
abort "service map" unless j.dig("serviceMap", "datasourceUid") == "prometheus"
abort "loki derived field" unless d["loki"]["derivedFields"].any? { |f| f["datasourceUid"] == "tempo" }
abort "prometheus exemplar" unless d["prometheus"]["exemplarTraceIdDestinations"].any? { |f| f["datasourceUid"] == "tempo" }
' "$T/tempo/tempo.yml" "$T/grafana/provisioning/datasources/datasources.yml" \
  && ok "tempo metrics_generator + grafana correlation jsonData" || bad "tempo metrics_generator / grafana correlation jsonData"

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
