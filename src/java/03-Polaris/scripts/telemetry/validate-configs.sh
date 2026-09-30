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
# ---- Gateway (plan O2a) ----
NGX_IMG=$(grep -o 'nginx:[0-9][0-9.]*-alpine-otel' docker-compose.yml | head -1)
[ -n "$NGX_IMG" ] && ok "nginx pinned to official -otel image ($NGX_IMG)" || bad "nginx not pinned to nginx:<version>-alpine-otel"
if [ -n "$NGX_IMG" ]; then
  HOSTS=""; for h in otel-collector polaris polaris-assistant keycloak grafana swagger-ui; do HOSTS="$HOSTS --add-host $h:127.0.0.1"; done
  # shellcheck disable=SC2086
  docker run --rm $HOSTS -v "$PWD/docker/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" -v "$PWD/docker/nginx/certs:/etc/nginx/certs:ro" "$NGX_IMG" nginx -t >/dev/null 2>&1 \
    && ok "nginx -t ($NGX_IMG)" || bad "nginx -t"
fi
N=docker/nginx/nginx.conf
grep -q 'load_module modules/ngx_otel_module.so' $N && grep -q 'otel_service_name nginx-gateway' $N \
  && grep -q 'otel_trace_context propagate' $N && grep -qE 'otel_exporter' $N && grep -q 'endpoint otel-collector:4317' $N \
  && ok "nginx exports OTLP spans (nginx-gateway, propagate traceparent)" || bad "nginx OTLP wiring"
grep -q 'access_log syslog:server=otel-collector:5514' $N && grep -q 'access_log /dev/stdout edge_json' $N \
  && ok "access log to syslog and stdout" || bad "access log wiring"
LF=$(sed -n '/log_format edge_json/,/;$/p' $N)
echo "$LF" | grep -q '\$otel_trace_id' && echo "$LF" | grep -q '\$otel_span_id' && echo "$LF" | grep -q '\$upstream_status' \
  && ! echo "$LF" | grep -qE '\$(args|query_string|request|request_uri|http_[a-z_]+|cookie_[a-z_]+|arg_[a-z_]+)\b' \
  && ok "log_format has trace ids and no query/header/cookie variables" || bad "log_format content"
grep -qF 'delete_matching_keys(span.attributes, "^http' $T/otel-collector-config.yaml && grep -q 'transform/nginx_spans' $T/otel-collector-config.yaml \
  && ok "collector strips the module's http.target (query string) from gateway spans" || bad "http.target not stripped in collector"
grep -q 'otel-collector:172.29.250.10' docker-compose.yml && grep -q 'ipv4_address: 172.29.250.10' docker-compose.override.yml \
  && ok "nginx maps otel-collector to its fixed address (no startup DNS dependency)" || bad "fixed collector address / extra_hosts"
[ "$(grep -c 'set \$sse "true"' $N)" -eq 2 ] && ok "SSE locations labelled (/mcp/, /api/v1/assistant)" || bad "SSE labels"
grep -q 'stub_status' $N && grep -q 'listen 8088' $N && ! grep -qE '"[0-9]*:?8088' docker-compose.yml docker-compose.override.yml \
  && ok "stub_status internal listener not published" || bad "stub_status listener / published"
ruby -ryaml -e '
c = YAML.load_file(ARGV[0]); ps = c["service"]["pipelines"]
req = ->(cond, msg) { abort(msg) unless cond }
req.(c["receivers"]["syslog"]["udp"]["listen_address"].end_with?(":5514"), "syslog receiver not on 5514")
l = ps.values.find { |p| p["receivers"].include?("syslog") }
req.(l && l["processors"][0] == "memory_limiter" && l["exporters"] == ["otlp_http/logs"], "syslog pipeline must start with memory_limiter and use the bounded logs exporter")
req.(ps.values.any? { |p| p["receivers"].include?("nginx") }, "no nginx stub_status metrics pipeline")
' "$T/otel-collector-config.yaml" && ok "collector syslog receiver + nginx metrics pipelines (policy applies)" || bad "collector syslog/nginx pipelines"
exit $fail
