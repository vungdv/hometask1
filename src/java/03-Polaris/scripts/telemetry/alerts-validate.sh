#!/usr/bin/env bash
# Static checks for alert metadata and wiring (plan O5, OBS-SLO-1). Needs only ruby. Run by validate-configs.sh.
#  - every alert has annotations summary, service, severity, dashboard_url, runbook_url and labels severity, owner
#  - runbook_url points at docs/operations/runbooks/<file>.md that EXISTS; no runbook without an alert
#  - dashboard_url points at a provisioned O4 dashboard uid
#  - Alertmanager config has no committed secrets; compose wiring (pinned images, healthchecks, loopback ports)
set -uo pipefail
cd "$(dirname "$0")/../.."
exec ruby -ryaml -rjson -e '
$fail = 0
def ok(m); puts "PASS #{m}"; end
def bad(m); puts "FAIL #{m}"; $fail = 1; end
R = "docker/telemetry/prometheus/rules"
RUNBOOKS = "docs/operations/runbooks"
uids = Dir["docker/telemetry/grafana/provisioning/dashboards/json/*/*.json"].map { |f| JSON.parse(File.read(f))["uid"] }
alerts = Dir["#{R}/*.yml"].flat_map { |f| (YAML.load_file(f)["groups"] || []).flat_map { |g| g["rules"].select { |r| r["alert"] }.map { |r| r.merge("_file" => f) } } }
alerts.empty? ? bad("no alerts found") : ok("#{alerts.size} alerts found")
REQ_ANN = %w[summary service severity dashboard_url runbook_url]
used = []
alerts.each do |a|
  n = a["alert"]; ann = a["annotations"] || {}; lab = a["labels"] || {}
  miss = REQ_ANN.reject { |k| ann[k].to_s.strip != "" }
  miss.empty? ? nil : bad("#{n}: missing annotations #{miss.join(", ")}")
  lab["severity"] ? nil : bad("#{n}: missing label severity")
  (lab["owner"] || a["expr"].include?("on (service, sli, owner)")) ? nil : bad("#{n}: no owner (static label, or inherited from the SLI series)")
  %w[page ticket].include?(lab["severity"]) ? nil : bad("#{n}: severity must be page or ticket")
  ann["severity"] == lab["severity"] ? nil : bad("#{n}: severity annotation differs from label")
  ru = ann["runbook_url"].to_s
  if ru =~ %r{/docs/operations/runbooks/([a-z0-9-]+)\.md\z}
    used << "#{$1}.md"
    File.exist?("#{RUNBOOKS}/#{$1}.md") ? nil : bad("#{n}: runbook_url points to a missing file #{RUNBOOKS}/#{$1}.md")
  else
    bad("#{n}: runbook_url must point to docs/operations/runbooks/<name>.md (got #{ru.inspect})")
  end
  seen = ann["dashboard_url"].to_s.scan(%r{/d/([A-Za-z0-9_-]+)}).flatten
  seen.empty? ? bad("#{n}: dashboard_url has no /d/<uid>") : seen.each { |u| uids.include?(u) ? nil : bad("#{n}: dashboard_url uid #{u} is not a provisioned dashboard") }
end
$fail == 0 ? ok("every alert has summary/service/severity/dashboard_url/runbook_url, existing runbook, provisioned dashboard uid") : nil
orph = Dir["#{RUNBOOKS}/*.md"].map { |f| File.basename(f) } - %w[README.md] - used
orph.empty? ? ok("no orphan runbooks") : bad("runbooks without an alert: #{orph.join(", ")}")
readme = File.read("#{RUNBOOKS}/README.md")
alerts.all? { |a| readme.include?("`#{a["alert"]}`") } ? ok("runbook README lists every alert") : bad("runbook README misses an alert")

am = YAML.load_file("docker/telemetry/alertmanager/alertmanager.yml")
flat = am.to_yaml
(flat !~ /(auth_password|auth_secret|auth_identity|password|token|api_url|routing_key|service_key|bearer)/i && flat !~ %r{https?://[^\s]*@}) \
  ? ok("alertmanager config has no committed credentials") : bad("alertmanager config contains credential-like keys")
recv = am["receivers"].to_h { |r| [r["name"], r] }
routes = am["route"]["routes"].flat_map { |s| s["routes"].map { |r| [s["matchers"][0][/severity="(\w+)"/, 1], r["matchers"][0][/owner="(\w+)"/, 1], r["receiver"]] } }
rok = routes.all? { |sev, own, rc| r = recv.fetch(rc); (r["email_configs"] || []).any? && (sev == "page" ? (r["webhook_configs"] || []).any? : r["webhook_configs"].nil?) }
rok && routes.size == 6 ? ok("routes: severity x owner -> receivers; page = email + webhook, ticket = email only") : bad("routing tree")
recv.values.flat_map { |r| r["email_configs"] || [] }.all? { |e| !e.key?("auth_password") } && am["global"]["smtp_smarthost"] == "mailpit:1025" ? ok("SMTP goes to mailpit:1025") : bad("smtp target")

c = File.read("docker-compose.override.yml"); y = YAML.load_file("docker-compose.override.yml")["services"]
%w[alertmanager mailpit alert-webhook].each do |s|
  sv = y[s]
  (sv && sv["image"] =~ /:v?\d+[\d.]*\z/ && sv["healthcheck"]) ? ok("#{s}: pinned image and healthcheck") : bad("#{s}: image not pinned or no healthcheck")
end
pub = %w[alertmanager mailpit alert-webhook].flat_map { |s| (y[s]["ports"] || []).map { |p| [s, p] } }
dp = YAML.load_file("docker-compose.dev-ports.yml")["services"]
dpp = %w[alertmanager mailpit].flat_map { |s| (dp[s]["ports"] || []) }
pub.empty? && dpp.all? { |p| p.start_with?("127.0.0.1:") } && !dpp.join.include?("1025") && dp["alert-webhook"].nil? \
  ? ok("alertmanager/mailpit/webhook publish no port in the base stack; UIs only on loopback in docker-compose.dev-ports.yml; SMTP and webhook never published") : bad("dev ports exposed beyond loopback or SMTP/webhook published")
pv = y["prometheus"]["volumes"].join(" ")
pv.include?("/prometheus/rules:/etc/prometheus/rules") ? ok("prometheus mounts the rules directory") : bad("prometheus does not mount rules")
p = YAML.load_file("docker/telemetry/prometheus/prometheus.yml")
(p["rule_files"] || []).include?("/etc/prometheus/rules/*.yml") && p["alerting"]["alertmanagers"][0]["static_configs"][0]["targets"] == ["alertmanager:9093"] \
  && p["scrape_configs"].any? { |s| s["job_name"] == "alertmanager" } ? ok("prometheus loads the rules, sends to alertmanager:9093 and scrapes it") : bad("prometheus rule_files / alerting / scrape")
exit $fail
'
