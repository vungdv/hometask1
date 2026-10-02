#!/usr/bin/env bash
# Validates telemetry configs with the pinned images (plan O1, O2). Needs docker only.
set -euo pipefail
cd "$(dirname "$0")/../.."
T=docker/telemetry
OTEL_IMG=$(grep -o 'otel/opentelemetry-collector-contrib:[0-9.]*' docker-compose.override.yml | head -1)
PROM_IMG=$(grep -o 'prom/prometheus:v[0-9.]*' docker-compose.override.yml | head -1)
TEMPO_IMG=$(grep -o 'BASE: grafana/tempo:[0-9.]*' docker-compose.override.yml | head -1 | sed 's/BASE: //')
AM_IMG=$(grep -o 'prom/alertmanager:v[0-9.]*' docker-compose.override.yml | head -1)
LOKI_IMG=$(grep -o 'grafana/loki:[0-9.]*' docker-compose.override.yml | head -1)
fail=0
ok() { echo "PASS $1"; }
bad() { echo "FAIL $1"; fail=1; }

for f in docker-compose.override.yml; do
  ! grep -E 'image:.*:latest|image: *[^:]+$' $f | grep -E 'prometheus|alertmanager|mailpit|echo|loki|tempo|grafana|otel' >/dev/null && ok "no unpinned telemetry image" || bad "unpinned telemetry image"
done
# Secrets are required (no committed default, plan O7), so the dummy values below only let `config` interpolate.
O7ENV="GRAFANA_ADMIN_PASSWORD=ci-dummy GRAFANA_OAUTH_CLIENT_SECRET=ci-dummy POLARIS_DOMAIN=polaris.local"
env $O7ENV docker compose -f docker-compose.yml -f docker-compose.override.yml config -q && ok "docker compose config" || bad "docker compose config"
env $O7ENV docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.dev-ports.yml config -q && ok "docker compose config with the dev-ports override" || bad "docker compose config with dev-ports"
env -u GRAFANA_ADMIN_PASSWORD -u GRAFANA_OAUTH_CLIENT_SECRET POLARIS_DOMAIN=polaris.local docker compose --env-file /dev/null -f docker-compose.yml -f docker-compose.override.yml config -q >/dev/null 2>&1 \
  && bad "compose starts without the Grafana secrets (a committed default has crept back)" || ok "compose refuses to start without GRAFANA_ADMIN_PASSWORD / GRAFANA_OAUTH_CLIENT_SECRET"

docker run --rm -v "$PWD/$T/otel-collector-config.yaml:/c.yaml:ro" "$OTEL_IMG" validate --config=/c.yaml >/dev/null \
  && ok "otelcol validate ($OTEL_IMG)" || bad "otelcol validate"

docker run --rm --entrypoint promtool -v "$PWD/$T/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" -v "$PWD/$T/prometheus/rules:/etc/prometheus/rules:ro" -v "$PWD/$T/prometheus/rules-test:/etc/prometheus/rules-test:ro" "$PROM_IMG" check config /etc/prometheus/prometheus.yml >/dev/null \
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
# ---- Redaction and cardinality guardrails (plan O3) ----
docker run --rm -v "$PWD/$T/loki/loki.yml:/c.yml:ro" "$LOKI_IMG" -config.file=/c.yml -verify-config >/dev/null 2>&1 \
  && ok "loki -verify-config ($LOKI_IMG)" || bad "loki -verify-config"
ruby -ryaml -e '
c = YAML.load_file(ARGV[0]); ps = c["service"]["pipelines"]
def req(cond, msg); abort(msg) unless cond; end
ps.each do |name, p|
  pr = p["processors"]
  req pr.include?("redaction/secrets"), "#{name}: redaction/secrets missing"
  req pr.index("redaction/secrets") > 0 && pr.index("redaction/secrets") < pr.index("batch"), "#{name}: redaction must run after memory_limiter and before batch/exporters"
  kind = name.split("/")[0]
  req pr.include?("transform/redact_logs"), "#{name}: transform/redact_logs missing" if kind == "logs"
  req pr.include?("transform/redact_traces"), "#{name}: transform/redact_traces missing" if kind == "traces"
  req pr.include?("transform/metric_allowlist"), "#{name}: metric attribute allow-list missing" if kind == "metrics"
end
r = c["processors"]["redaction/secrets"]
req r["blocked_key_patterns"].join =~ /authorization/ && r["blocked_key_patterns"].join =~ /cookie/, "authorization/cookie key patterns missing"
req r["blocked_values"].size >= 4, "PII/secret value patterns missing (email, card, bearer, jwt)"
al = c["processors"]["transform/metric_allowlist"]["metric_statements"][0]["statements"].join
req al.include?("keep_matching_keys(datapoint.attributes"), "metric allow-list must use keep_matching_keys"
req !al.match?(/keep_matching_keys[^\n]*\((user|order)\b|\|(user|order)\|/), "user/order must not be allow-listed on metrics"
' "$T/otel-collector-config.yaml" && ok "collector redaction on every pipeline (before batch/exporter) + metric attribute allow-list" || bad "collector redaction/allow-list policy"
ruby -ryaml -e '
l = YAML.load_file(ARGV[0])["limits_config"]
def req(cond, msg); abort(msg) unless cond; end
a = l["otlp_config"]["resource_attributes"]
req a["ignore_defaults"] == true, "loki otlp ignore_defaults must be true"
req a["attributes_config"].select { |x| x["action"] == "index_label" }.flat_map { |x| x["attributes"] }.sort == %w[deployment.environment service.name service.namespace], "loki index labels must be exactly service.name, service.namespace, deployment.environment"
req l["max_label_names_per_series"].to_i.between?(1, 15) && l["max_global_streams_per_user"].to_i.positive?, "loki label/stream limits missing"
' "$T/loki/loki.yml" && ok "loki index labels limited to 3 resource attributes + label/stream limits" || bad "loki label/stream limits"
[ -x scripts/telemetry/redaction-check.sh ] && ok "live redaction test present (scripts/telemetry/redaction-check.sh)" || bad "redaction-check.sh missing"
# ---- Dashboards as code (plan O4) ----
scripts/telemetry/validate-dashboards.sh >/dev/null && ok "dashboards: parse, datasources resolve, units, no forbidden labels (scripts/telemetry/validate-dashboards.sh)" || bad "dashboards validation (run scripts/telemetry/validate-dashboards.sh)"
# ---- SLOs, alerts and runbooks (plan O5) ----
docker run --rm --entrypoint sh -v "$PWD/$T/prometheus/rules:/r:ro" "$PROM_IMG" -c 'promtool check rules /r/*.yml' >/dev/null \
  && ok "promtool check rules ($PROM_IMG)" || bad "promtool check rules"
docker run --rm --entrypoint sh -v "$PWD/$T/prometheus:/p:ro" "$PROM_IMG" -c 'cd /p/tests && promtool test rules *.test.yml' >/tmp/promtool-test.$$ 2>&1 \
  && ok "promtool test rules: fast burn, slow burn, no-alert, history and traffic guards, normalisation, pipeline alerts ($(grep -c SUCCESS /tmp/promtool-test.$$) suites)" || { cat /tmp/promtool-test.$$; bad "promtool test rules"; }
rm -f /tmp/promtool-test.$$
docker run --rm --entrypoint amtool -v "$PWD/$T/alertmanager/alertmanager.yml:/a.yml:ro" "$AM_IMG" check-config /a.yml >/dev/null \
  && ok "amtool check-config ($AM_IMG)" || bad "amtool check-config"
route() { docker run --rm --entrypoint amtool -v "$PWD/$T/alertmanager/alertmanager.yml:/a.yml:ro" "$AM_IMG" config routes test --config.file=/a.yml --verify.receivers="$1" "${@:2}" >/dev/null 2>&1; }
route platform-page severity=page owner=platform && route polaris-page severity=page owner=polaris && route assistant-page severity=page owner=assistant \
  && route platform-ticket severity=ticket owner=platform && route polaris-ticket severity=ticket owner=polaris && route assistant-ticket severity=ticket owner=assistant \
  && route catch-all severity=unknown \
  && ok "amtool routes: severity x owner reach the expected receiver, unknown falls to catch-all" || bad "amtool routes test"
scripts/telemetry/alerts-validate.sh >/dev/null && ok "alert metadata, runbook files, dashboard uids, dev wiring (scripts/telemetry/alerts-validate.sh)" || bad "alerts validation (run scripts/telemetry/alerts-validate.sh)"
[ -x scripts/telemetry/alerts-check.sh ] && ok "live failure-injection check present (scripts/telemetry/alerts-check.sh)" || bad "alerts-check.sh missing"
[ -x scripts/telemetry/async-check.sh ] && ok "live outbox/Kafka failure-injection check present (scripts/telemetry/async-check.sh)" || bad "async-check.sh missing"
# ---- Access, retention and exposure (plan O7, OBS-SEC-1) ----
# Old committed credentials must not reappear anywhere that ships (compose, realm, configs, scripts, README, operations docs).
OLD1="grafana-client-""secret"; OLD2="admin""/admin"; OLD3="admin"":admin"; OLD4="GRAFANA_PASSWORD=""admin"; OLD5="ADMIN_PASSWORD=""admin"
if grep -rnE "$OLD1|$OLD2|$OLD3|$OLD4|GF_SECURITY_$OLD5" docker-compose.yml docker-compose.override.yml docker-compose.dev-ports.yml docker README.md .env.template docs/operations scripts Makefile gcx.sh \
     --exclude=validate-configs.sh | grep -v 'KEYCLOAK_ADMIN_PASSWORD' | grep -v 'Keycloak Admin'; then
  bad "old inline credentials reappeared (matches above); use .env / scripts/init-env.sh"
else ok "no inline Grafana admin password or OAuth client secret in committed files"; fi
grep -q '"secret": "${GRAFANA_OAUTH_CLIENT_SECRET}"' docker/keycloak/master-realm.json && ! grep -q '"clientId": "grafana"' docker/keycloak/polaris-realm.json \
  && grep -q 'GRAFANA_OAUTH_CLIENT_SECRET:' docker-compose.yml && grep -q 'import/master-realm.json' docker-compose.yml \
  && ok "grafana client is imported into the master realm with its secret from the environment; none in polaris" || bad "grafana client must be only in docker/keycloak/master-realm.json with an env-substituted secret"
grep -q 'realms/master/protocol' docker-compose.override.yml && ! grep -q 'realms/polaris/protocol' docker-compose.override.yml \
  && ok "Grafana SSO points at the master realm" || bad "Grafana SSO endpoints are not the master realm"
ruby -ryaml -rjson -e '
def req(c, m); abort(m) unless c; end
base = YAML.load_file("docker-compose.override.yml")["services"]; main = YAML.load_file("docker-compose.yml")["services"]
dev = YAML.load_file("docker-compose.dev-ports.yml")["services"]
%w[prometheus loki tempo otel-collector grafana alertmanager mailpit alert-webhook].each { |s| req base[s]["ports"].nil?, "#{s} publishes a host port in the base stack" }
req dev.values.flat_map { |v| v["ports"] }.all? { |p| p.start_with?("127.0.0.1:") }, "dev-ports publishes beyond loopback"
req main["nginx"]["ports"] == ["80:80", "443:443"], "nginx must be the only published entrypoint of the telemetry path"
g = base["grafana"]["environment"].to_h { |e| e.split("=", 2) }
{ "GF_USERS_VIEWERS_CAN_EDIT" => "false", "GF_USERS_EDITORS_CAN_ADMIN" => "false", "GF_AUTH_ANONYMOUS_ENABLED" => "false",
  "GF_AUTH_GENERIC_OAUTH_ROLE_ATTRIBUTE_STRICT" => "true", "GF_USERS_ALLOW_SIGN_UP" => "false", "GF_SECURITY_COOKIE_SECURE" => "true" }.each { |k, v| req g[k] == v, "#{k} must be #{v}" }
req g["GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET"].include?("${GRAFANA_OAUTH_CLIENT_SECRET:?") && g["GF_SECURITY_ADMIN_PASSWORD"].include?("${GRAFANA_ADMIN_PASSWORD:?"), "grafana secrets must come from required env vars"
req !g["GF_AUTH_GENERIC_OAUTH_ROLE_ATTRIBUTE_PATH"].match?(/\|\| \x27Viewer\x27\z/), "oauth role mapping must not fall back to Viewer"
ds = YAML.load_file("docker/telemetry/grafana/provisioning/datasources/datasources.yml")["datasources"]
req ds.all? { |d| d["editable"] == false }, "every provisioned datasource must set editable: false"
fp = JSON.parse(File.read("docker/telemetry/grafana/access/folder-permissions.json"))["folders"]
dirs = Dir["docker/telemetry/grafana/provisioning/dashboards/json/*"].select { |d| File.directory?(d) }.map { |d| File.basename(d) }.sort
req fp.keys.sort == dirs, "folder-permissions.json must cover exactly the dashboard folders #{dirs}"
req fp.values.none? { |v| v.key?("Viewer") && v["Viewer"] != "View" } && fp.values.all? { |v| v.values.all? { |p| %w[View Edit Admin].include?(p) } }, "folder permission levels"
req !fp["platform"].key?("Viewer") && !fp["engineering"].key?("Viewer"), "platform/engineering folders must not be visible to Viewers"
req base["grafana-access-init"], "grafana-access-init service missing"
' && ok "no backend port published in the base stack; dev-ports loopback only; grafana access settings, datasource editable:false and folder permissions declared" || bad "exposure/access policy (see message above)"
python3 -m py_compile docker/telemetry/grafana/access/apply-folder-permissions.py && ok "folder-permission applier compiles" || bad "apply-folder-permissions.py"
rm -rf docker/telemetry/grafana/access/__pycache__
# Retention per signal (metrics 15d + 5GB, traces 3d, logs 7d) is configured, with ingest caps
grep -q -- '--storage.tsdb.retention.time=15d' docker-compose.override.yml && grep -q -- '--storage.tsdb.retention.size=5GB' docker-compose.override.yml \
  && ok "prometheus retention 15d + 5GB size cap" || bad "prometheus retention flags"
ruby -ryaml -e '
def req(c, m); abort(m) unless c; end
l = YAML.load_file(ARGV[0]); t = YAML.load_file(ARGV[1])
req l["limits_config"]["retention_period"] == "168h" && l["compactor"]["retention_enabled"] == true && l["compactor"]["delete_request_store"], "loki retention (7d) not enforced by the compactor"
req l["limits_config"]["ingestion_rate_mb"] && l["limits_config"]["per_stream_rate_limit"], "loki ingest caps missing"
req t["backend_scheduler"]["provider"]["compaction"]["compaction"]["block_retention"] == "72h" && t["backend_worker"]["compaction"]["block_retention"] == "72h", "tempo block_retention must be 72h on scheduler and worker"
req t["overrides"]["defaults"]["ingestion"]["rate_limit_bytes"] && t["overrides"]["defaults"]["global"]["max_bytes_per_trace"], "tempo ingest caps missing"
' "$T/loki/loki.yml" "$T/tempo/tempo.yml" && ok "loki 7d (compactor-enforced) and tempo 3d retention + ingest caps" || bad "loki/tempo retention"
[ -x scripts/telemetry/access-check.sh ] && ok "live access check present (scripts/telemetry/access-check.sh)" || bad "access-check.sh missing"
exit $fail
