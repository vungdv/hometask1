#!/usr/bin/env bash
# OPT-IN, DESTRUCTIVE fresh-clone bootstrap verification (plan O8, O7 reviewer request).
#
# THIS DELETES EVERY VOLUME OF THE COMPOSE STACK (`make clean` = `docker compose down --volumes --remove-orphans`): Postgres,
# Keycloak realm and users, Grafana, Prometheus, Tempo, Loki and Kafka data. It also removes .env so scripts/init-env.sh must
# generate it. Never run it on a stack you want to keep. It is NOT part of run.sh or failure-injection.sh.
#
# Proves, from nothing: `make up` (1) generates .env with non-empty GRAFANA_ADMIN_PASSWORD / GRAFANA_OAUTH_CLIENT_SECRET,
# (2) imports the Keycloak master realm with ${GRAFANA_OAUTH_CLIENT_SECRET} substituted into its grafana client (Grafana SSO login
# works, which only works when both sides hold the same secret), (3) every container becomes healthy, (4) the O8 end-to-end
# run passes on the empty stack (dev users and SKU are created by tests/e2e/run-fulfilment.sh first).
# Usage: tests/e2e/observability/fresh-bootstrap.sh --yes-delete-all-volumes
#        then type DELETE at the prompt (or set CONFIRM_DELETE=DELETE for unattended use)
# Env:   UP_SECS (900) health wait after make up;  KEEP_ENV=1 keeps an existing .env (tests only the volumes path)
set -uo pipefail
SELF=$(cd "$(dirname "$0")" && pwd)/$(basename "$0")
cd "$(dirname "$0")/../../.."
[ "${1:-}" = "--yes-delete-all-volumes" ] || { sed -n '2,15p' "$SELF"; echo; echo "Refusing: pass --yes-delete-all-volumes to proceed."; exit 2; }
if [ "${CONFIRM_DELETE:-}" != DELETE ]; then
  echo "About to delete ALL volumes of the compose stack in $PWD."
  printf 'Type DELETE to continue: '; read -r a; [ "$a" = DELETE ] || { echo "Aborted."; exit 2; }
fi
. tests/e2e/observability/lib.sh
LOGS=$(mktemp -d)
if [ "${KEEP_ENV:-0}" != 1 ]; then mv .env ".env.bak.$(date +%s)" 2>/dev/null && info "previous .env moved aside"; fi
make clean && ok "make clean" || { bad "make clean"; exit 1; }
make up > $LOGS/up.log 2>&1 && ok "make up" || { bad "make up (see $LOGS/up.log)"; exit 1; }
for k in GRAFANA_ADMIN_PASSWORD GRAFANA_OAUTH_CLIENT_SECRET; do
  [ -n "$(envval $k)" ] && ok ".env has a generated $k" || bad ".env lacks $k"
done
out=$(wait_stack_healthy "${UP_SECS:-900}") && ok "all stack containers healthy on an empty volume set" || { bad "stack not healthy: $out"; exit 1; }
SECRET=$(envval GRAFANA_OAUTH_CLIENT_SECRET); GRAFANA_ADMIN_PASSWORD=$(envval GRAFANA_ADMIN_PASSWORD)
TOKEN=$(docker exec keycloak sh -c 'true' >/dev/null 2>&1; gw -d grant_type=client_credentials -d client_id=grafana --data-urlencode "client_secret=$SECRET" \
  https://id.polaris.local/realms/master/protocol/openid-connect/token | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
[ -n "$TOKEN" ] && ok "Keycloak accepts the .env grafana client secret (master realm import substituted \${GRAFANA_OAUTH_CLIENT_SECRET})" \
  || info "client_credentials grant not enabled for the grafana client; the SSO login in access-check.sh covers the secret instead"
docker logs keycloak 2>&1 | grep -q "Realm 'master' imported" && ok "Keycloak imported the master realm from master-realm.json" \
  || bad "Keycloak did not import master-realm.json (volume not empty, or file name not *-realm.json)"
./scripts/telemetry/access-check.sh > $LOGS/access.log 2>&1 && ok "access-check.sh (SSO logins prove Grafana and Keycloak share the secret)" || { bad "access-check.sh (see $LOGS/access.log)"; }
RUN_FULFILMENT=1 ./tests/e2e/observability/run.sh && ok "run.sh on the fresh stack" || bad "run.sh on the fresh stack"
echo; [ $fail -eq 0 ] && echo "PASS" || echo "FAIL"; exit $fail
