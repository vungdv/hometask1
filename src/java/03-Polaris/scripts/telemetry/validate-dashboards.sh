#!/usr/bin/env bash
# Validates the provisioned Grafana dashboards (plan O4). Needs only ruby (as validate-configs.sh).
#   scripts/telemetry/validate-dashboards.sh                 static checks (CI)
#   scripts/telemetry/validate-dashboards.sh --live [URL]    also run every Prometheus query against
#                                                            a live Prometheus. Default: inside polaris-net via the
#                                                            grafana container (no host port, plan O7); with a URL
#                                                            argument (e.g. the dev-ports http://127.0.0.1:9090): host HTTP
# Static checks: provider config, JSON parses, unique uids, every datasource reference resolves to a UID
# in provisioning/datasources, no query uses a forbidden label (O3), recorded units on value panels,
# `service` and `env` template variables present (except the pipeline dashboard).
set -euo pipefail
cd "$(dirname "$0")/../.."
LIVE=""; PROM_URL=""
if [ "${1:-}" = "--live" ]; then LIVE=1; PROM_URL="${2:-}"; fi
export LIVE PROM_URL
exec ruby -rjson -ryaml -rnet/http -ruri -e '
G = "docker/telemetry/grafana/provisioning"
$fail = 0
def ok(m); puts "PASS #{m}"; end
def bad(m); puts "FAIL #{m}"; $fail = 1; end

prov = YAML.load_file("#{G}/dashboards/dashboards.yml")["providers"].first
prov["options"]["foldersFromFilesStructure"] ? ok("provider: folders from directory structure") : bad("provider must set foldersFromFilesStructure")
prov["allowUiUpdates"] == false ? ok("provider: dashboards read-only in UI") : bad("provider allowUiUpdates must be false")
prov["options"]["path"] == "/etc/grafana/provisioning/dashboards/json" ? ok("provider path is under the mounted provisioning dir") : bad("provider path")
compose = File.read("docker-compose.override.yml")
compose.include?("./docker/telemetry/grafana/provisioning:/etc/grafana/provisioning") ? ok("compose mounts the provisioning dir into grafana") : bad("compose does not mount provisioning dir")

uids = YAML.load_file("#{G}/datasources/datasources.yml")["datasources"].map { |d| d["uid"] }
FORBIDDEN = /(?<![A-Za-z0-9_.])(user[_.]?id|userid|user[_.]name|order[_.]?(number|id|no|status|total|item)[A-Za-z0-9_.]*|order_[A-Za-z0-9_]+|order\.[A-Za-z0-9_.]+|orderNumber)(?![A-Za-z0-9])/i
files = Dir["#{G}/dashboards/json/*/*.json"].sort
files.empty? ? bad("no dashboard JSON found") : ok("#{files.size} dashboard files found")
seen = {}; queries = []
NEEDS_UNIT = %w[timeseries stat gauge bargauge barchart]
EXEMPT_VARS = %w[polaris-telemetry-pipeline]

def each_hash(o, &b)
  case o
  when Hash then b.call(o); o.each_value { |v| each_hash(v, &b) }
  when Array then o.each { |v| each_hash(v, &b) }
  end
end

files.each do |f|
  name = f.sub("#{G}/dashboards/json/", "")
  begin
    d = JSON.parse(File.read(f))
  rescue JSON::ParserError => e
    bad("#{name}: JSON does not parse (#{e.message[0,80]})"); next
  end
  errs = []
  errs << "missing uid/title" unless d["uid"] && d["title"]
  errs << "uid #{d["uid"]} duplicated in #{seen[d["uid"]]}" if seen[d["uid"]]
  seen[d["uid"]] = name
  errs << "file name must be <uid>.json" unless File.basename(f, ".json") == d["uid"]
  errs << "dashboard must be tagged polaris" unless (d["tags"] || []).include?("polaris")
  vars = (d.dig("templating", "list") || []).map { |v| v["name"] }
  varnames = vars
  unless EXEMPT_VARS.include?(d["uid"])
    %w[service env].each { |v| errs << "template variable #{v} missing" unless vars.include?(v) }
  end
  each_hash(d) do |h|
    if h.key?("datasource")
      ds = h["datasource"]
      if ds.is_a?(Hash)
        u = ds["uid"].to_s
        errs << "datasource uid #{u.inspect} not provisioned" unless uids.include?(u) || u =~ /\A\$\{?\w+\}?\z/ || u == "-- Grafana --"
      elsif !ds.nil?
        errs << "datasource must be a {type,uid} object, got #{ds.inspect}"
      end
    end
  end
  (d["panels"] || []).each do |p|
    next if p["type"] == "row"
    title = "#{p["type"]} #{p["title"].inspect}"
    if NEEDS_UNIT.include?(p["type"])
      u = p.dig("fieldConfig", "defaults", "unit")
      errs << "#{title}: no unit recorded" if u.nil? || u.empty?
    end
    (p["targets"] || []).each do |t|
      q = t["expr"] || t["query"]
      next unless q
      errs << "#{title}: forbidden label in query: #{q[FORBIDDEN]}" if q =~ FORBIDDEN
      errs << "#{title}: target without datasource" unless (t["datasource"] || p["datasource"])
      queries << [name, p["title"], q, (t["datasource"] || p["datasource"])["uid"]] if (t["datasource"] || p["datasource"]).to_h["uid"] == "prometheus" && t["expr"]
    end
  end
  (d.dig("templating", "list") || []).each do |v|
    q = v["query"].is_a?(Hash) ? v["query"].values.join(" ") : v["query"].to_s
    errs << "variable #{v["name"]}: forbidden label" if q =~ FORBIDDEN
  end
  errs.empty? ? ok("#{name}: parses, datasources resolve, units recorded, no forbidden labels, variables present") : errs.each { |e| bad("#{name}: #{e}") }
end

if ENV["LIVE"] == "1"
  base = ENV["PROM_URL"]; empty = 0
  query = lambda do |e|
    if base.empty?  # no published host port: run curl inside the grafana container on polaris-net
      JSON.parse(IO.popen(["docker", "exec", "grafana", "curl", "-s", "-m", "30", "--data-urlencode", "query=#{e}", "http://prometheus:9090/api/v1/query"], &:read))
    else
      JSON.parse(Net::HTTP.post_form(URI("#{base}/api/v1/query"), "query" => e).body)
    end
  end
  queries.each do |(dash, title, q, _)|
    e = q.gsub("$__rate_interval", "5m").gsub(/\$service\b/, ".+").gsub(/\$env\b/, ".*").gsub(/\$(event_type|group)\b/, ".+")
    r = query.call(e)
    if r["status"] != "success" then bad("live #{dash} / #{title}: #{r["error"]}")
    elsif r["data"]["result"].empty? then empty += 1; puts "WARN live #{dash} / #{title}: no data"
    else ok("live #{dash} / #{title}: #{r["data"]["result"].size} series")
    end
  end
  puts "live: #{queries.size} queries, #{empty} without data (may be legitimately empty, e.g. error rates)"
end
exit $fail
' 
