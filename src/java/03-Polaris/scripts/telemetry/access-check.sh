#!/usr/bin/env bash
# OBS-SEC-1 verification (plan O7). Needs the stack up (make up), docker, curl and python3. Not run in CI.
# Asserts on the LIVE stack:
#   exposure   no telemetry backend publishes a host port (or, with docker-compose.dev-ports.yml, only on 127.0.0.1), and
#              the host cannot connect to 3000/3100/3200/4317/4318/4319/8889/9090/9093/8025
#   secrets    the old committed Grafana admin password does not log in; the .env one does
#   roles      real Keycloak logins (OIDC authorization-code flow scripted with curl inside polaris-net) for throw-away users
#              in the MASTER realm with the grafana client roles viewer / editor / no role: Viewer cannot edit or add a
#              datasource and has no Explore permission, sees only the `operations` folder; Editor has Explore, still cannot
#              edit datasources, sees all folders; a master user with no role cannot log in; a polaris realm user (testuser,
#              a polaris admin) cannot log in; grafana client role admin -> Admin
# The throw-away Keycloak users (and the Grafana users their first login creates) are removed on exit.
# KNOWN LIMIT (printed as INFO): Grafana OSS has no per-datasource query permission, so a Viewer can still POST
# /api/ds/query to Loki (dashboard panels need it). docs/operations/access.md explains.
# Usage: scripts/telemetry/access-check.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
fail=0
ok()   { echo "PASS $1"; }
bad()  { echo "FAIL $1"; fail=1; }
info() { echo "INFO $1"; }
envval() { grep -E "^$1=" .env 2>/dev/null | tail -1 | cut -d= -f2-; }
GU=$(envval GRAFANA_ADMIN_USER); GU=${GU:-admin}; GP=$(envval GRAFANA_ADMIN_PASSWORD)
CA=/etc/grafana/certs/rootCA.pem; GW=https://grafana.polaris.local
RUN=$(openssl rand -hex 3); PW="Pw-$(openssl rand -hex 8)"
USERS=("o7-viewer-$RUN:viewer" "o7-editor-$RUN:editor" "o7-admin-$RUN:admin" "o7-norole-$RUN:")
KC="docker exec keycloak /opt/keycloak/bin/kcadm.sh"
cleanup() {
  for ur in "${USERS[@]}"; do
    id=$($KC get users -r master -q "username=${ur%%:*}" --fields id --format csv --noquotes 2>/dev/null | tr -d '\r')
    [ -n "$id" ] && $KC delete "users/$id" -r master >/dev/null 2>&1
    docker exec grafana rm -f "/tmp/o7-${ur%%:*}.jar" >/dev/null 2>&1
    gid=$(docker exec grafana curl -s -m 10 -u "$GU:$GP" "http://grafana:3000/api/users/lookup?loginOrEmail=${ur%%:*}@example.test" </dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])' 2>/dev/null)
    [ -n "$gid" ] && docker exec grafana curl -s -m 10 -u "$GU:$GP" -X DELETE "http://grafana:3000/api/admin/users/$gid" </dev/null >/dev/null 2>&1
  done
}
trap cleanup EXIT

# ---- exposure ----
BACKENDS="prometheus loki tempo otel-collector grafana alertmanager mailpit alert-webhook"
published=""; nonloop=""
for c in $BACKENDS; do
  while read -r line; do
    [ -z "$line" ] && continue; published="$published $c($line)"
    case "$line" in *"-> 127.0.0.1:"*) ;; *) nonloop="$nonloop $c($line)";; esac
  done < <(docker port "$c" 2>/dev/null)
done
if [ -z "$published" ]; then ok "no telemetry backend publishes a host port (docker port)"
elif [ -z "$nonloop" ]; then ok "dev-ports override active: published only on 127.0.0.1:$published"
else bad "backend ports published beyond loopback:$nonloop"; fi
if [ -z "$published" ]; then
  closed=1; for p in 3000 3100 3200 4317 4318 4319 8889 9090 9093 8025; do
    nc -z -w 1 127.0.0.1 "$p" 2>/dev/null && { bad "host can connect to 127.0.0.1:$p"; closed=0; }
  done; [ $closed = 1 ] && ok "host cannot connect to 3000 3100 3200 4317 4318 4319 8889 9090 9093 8025"
fi
code=$(curl -sk -m 10 -o /dev/null -w '%{http_code}' --resolve grafana.polaris.local:443:127.0.0.1 https://grafana.polaris.local/api/health)
[ "$code" = 200 ] && ok "Grafana is reachable through the gateway (https://grafana.polaris.local)" || bad "Grafana not reachable through the gateway ($code)"

# ---- secrets ----
ac() { docker exec -i grafana curl -s -m 15 "$@" </dev/null; }
[ -n "$GP" ] || bad ".env has no GRAFANA_ADMIN_PASSWORD (run scripts/init-env.sh)"
[ "$(ac -o /dev/null -w '%{http_code}' -u "$GU:$GP" http://grafana:3000/api/org)" = 200 ] \
  && ok "the .env admin password works" || bad "the .env admin password does not log in (volume predates the .env value: make clean && make up)"
ac -u "$GU:$GP" http://grafana:3000/api/datasources | python3 -c '
import sys, json
d = json.load(sys.stdin); assert len(d) == 3 and all(x["readOnly"] for x in d), d' 2>/dev/null \
  && ok "all 3 datasources are provisioned and read-only" || bad "datasources are not all provisioned read-only"
[ "$(ac -o /dev/null -w '%{http_code}' -u "$GU:$GP" -X PUT -H 'Content-Type: application/json' http://grafana:3000/api/datasources/uid/loki -d '{"name":"Loki","type":"loki","url":"http://evil:3100","access":"proxy"}')" = 403 ] \
  && ok "even the Admin cannot edit a provisioned datasource through the API" || bad "provisioned datasource editable"

# ---- Keycloak users and scripted SSO ----
KCPW=$(docker exec keycloak printenv KEYCLOAK_ADMIN_PASSWORD); KCU=$(docker exec keycloak printenv KEYCLOAK_ADMIN)
$KC config credentials --server http://localhost:8080 --realm master --user "$KCU" --password "$KCPW" >/dev/null 2>&1 || { bad "kcadm login failed"; exit 1; }
for ur in "${USERS[@]}"; do
  u=${ur%%:*}; r=${ur#*:}
  $KC create users -r master -s "username=$u" -s enabled=true -s "email=$u@example.test" -s firstName=O7 -s lastName=Check >/dev/null 2>&1 \
    && $KC set-password -r master --username "$u" --new-password "$PW" >/dev/null 2>&1 \
    && { [ -z "$r" ] || $KC add-roles -r master --uusername "$u" --cclientid grafana --rolename "$r" >/dev/null 2>&1; } || bad "could not create keycloak user $u"
done

login() { # user password -> prints the final HTTP status; the session cookie stays in /tmp/o7-<user>.jar inside grafana
  docker exec -i grafana sh -s -- "$1" "$2" <<'SH'
U=$1; P=$2; J=/tmp/o7-$U.jar; CA=/etc/grafana/certs/rootCA.pem; G=https://grafana.polaris.local
rm -f "$J"
HTML=$(curl -s --cacert $CA -c $J -b $J -L -m 30 $G/login/generic_oauth)
ACTION=$(echo "$HTML" | grep -o 'action="[^"]*"' | head -1 | sed 's/^action="//; s/"$//; s/&amp;/\&/g')
[ -n "$ACTION" ] || { echo noform; exit 0; }
curl -s --cacert $CA -c $J -b $J -L -m 30 -o /dev/null -w '%{http_code}' --data-urlencode "username=$U" --data-urlencode "password=$P" "$ACTION"
SH
}
sc() { local u=$1; shift; docker exec -i grafana curl -s -m 20 --cacert "$CA" -b "/tmp/o7-$u.jar" "$@" </dev/null; }
sso_role() { sc "$1" $GW/api/user/orgs | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["role"])' 2>/dev/null; }

VU="o7-viewer-$RUN"; EU="o7-editor-$RUN"; AU="o7-admin-$RUN"; SU="o7-norole-$RUN"
login "$VU" "$PW" >/dev/null; login "$EU" "$PW" >/dev/null; login "$SU" "$PW" >/dev/null
[ "$(sso_role "$VU")" = Viewer ] && ok "master grafana client role viewer -> Grafana Viewer (real SSO login)" || bad "viewer SSO login did not yield role Viewer"
[ "$(sso_role "$EU")" = Editor ] && ok "master grafana client role editor -> Grafana Editor (real SSO login)" || bad "editor SSO login did not yield role Editor"
sc "$SU" $GW/api/user 2>/dev/null | grep -q '"login"' && bad "a master user with no Grafana role could log in" || ok "a master user with no grafana client role cannot log in (strict role mapping)"
login testuser testpass >/dev/null
sc testuser $GW/api/user 2>/dev/null | grep -q '"login"' && bad "a polaris realm user (testuser) could log in to Grafana" || ok "a polaris realm user (testuser, a polaris admin) cannot log in: Grafana SSO is the master realm only"
docker exec grafana rm -f /tmp/o7-testuser.jar >/dev/null 2>&1
login "$AU" "$PW" >/dev/null
[ "$(sso_role "$AU")" = Admin ] && ok "master grafana client role admin -> Grafana Admin (real SSO login)" || bad "admin SSO login did not yield role Admin"

perm() { sc "$1" $GW/api/access-control/user/permissions | python3 -c 'import sys,json;d=json.load(sys.stdin);print(" ".join(sorted(k for k in d if k.startswith("datasources"))))' 2>/dev/null; }
code() { local u=$1; shift; sc "$u" -o /dev/null -w '%{http_code}' "$@"; }
BODY='{"name":"Loki","type":"loki","url":"http://evil:3100","access":"proxy"}'
for u in "$VU" "$EU"; do
  r=Viewer; [ "$u" = "$EU" ] && r=Editor
  [ "$(code "$u" -X PUT -H 'Content-Type: application/json' $GW/api/datasources/uid/loki -d "$BODY")" = 403 ] && ok "$r cannot edit the Loki datasource (403)" || bad "$r can edit a datasource"
  [ "$(code "$u" -X POST -H 'Content-Type: application/json' $GW/api/datasources -d "$BODY")" = 403 ] && ok "$r cannot add a datasource (403)" || bad "$r can add a datasource"
  [ "$(code "$u" -X DELETE $GW/api/datasources/uid/loki)" = 403 ] && ok "$r cannot delete a datasource (403)" || bad "$r can delete a datasource"
done
case " $(perm "$VU") " in *" datasources:explore "*) bad "Viewer has the Explore permission";; *) ok "Viewer has no datasources:explore (Explore of Loki/Tempo/Prometheus is Editor/Admin only)";; esac
case " $(perm "$EU") " in *" datasources:explore "*) ok "Editor has datasources:explore (needs it to investigate logs)";; *) bad "Editor lacks Explore";; esac
folders() { sc "$1" $GW/api/folders | python3 -c 'import sys,json;print(" ".join(sorted(f["title"] for f in json.load(sys.stdin) if f["title"] != "Shared with me")))' 2>/dev/null; }
[ "$(folders "$VU")" = operations ] && ok "Viewer sees only the operations dashboard folder" || bad "Viewer folders: '$(folders "$VU")'"
[ "$(folders "$EU")" = "engineering operations platform" ] && ok "Editor sees operations, engineering and platform folders" || bad "Editor folders: '$(folders "$EU")'"
PUID=$(ac -u "$GU:$GP" "http://grafana:3000/api/search?type=dash-db&query=pipeline" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["uid"])' 2>/dev/null)
OUID=$(ac -u "$GU:$GP" "http://grafana:3000/api/search?type=dash-db&query=edge" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["uid"])' 2>/dev/null)
[ -n "$PUID" ] && [ "$(code "$VU" $GW/api/dashboards/uid/$PUID)" = 403 ] && ok "Viewer cannot open a platform dashboard (403)" || bad "Viewer can open a platform dashboard ($PUID)"
[ -n "$OUID" ] && [ "$(code "$VU" $GW/api/dashboards/uid/$OUID)" = 200 ] && ok "Viewer can open an operations dashboard" || bad "Viewer cannot open an operations dashboard ($OUID)"
DBODY='{"dashboard":{"uid":"'"$OUID"'","title":"x"},"overwrite":true}'
[ -n "$OUID" ] && [ "$(code "$VU" -g -X POST -H 'Content-Type: application/json' $GW/api/dashboards/db -d "$DBODY")" = 403 ] \
  && ok "Viewer cannot overwrite a dashboard (403)" || bad "Viewer can write a dashboard"
q='{"queries":[{"refId":"A","datasource":{"uid":"loki","type":"loki"},"expr":"{service_name=~\".+\"}","queryType":"range"}],"from":"now-5m","to":"now"}'
info "known limit: Viewer POST /api/ds/query to Loki returns $(code "$VU" -X POST -H 'Content-Type: application/json' $GW/api/ds/query -d "$q") (OSS has no per-datasource query permission; see docs/operations/access.md)"
# Last on purpose: a wrong password counts towards Grafana's 5-attempt login lockout (5 minutes) for the admin user.
old=$(printf 'admin:%s' admin); [ "$(ac -o /dev/null -w '%{http_code}' -u "$old" http://grafana:3000/api/org)" = 401 ] \
  && ok "the old committed admin password is rejected" || bad "the old admin password still works"
exit $fail
