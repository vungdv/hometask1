#!/usr/bin/env bash
# Pushes the secrets in .env into EXISTING volumes (plan O7). A fresh stack needs none of this: Grafana reads
# GF_SECURITY_ADMIN_PASSWORD on first start and Keycloak imports the realm with the substituted client secret. On a stack that
# already has volumes (or after you rotate a value in .env) both stores keep the OLD value until synced:
#   1. Grafana local admin password   -> `grafana cli admin reset-admin-password` inside the grafana container
#   2. Keycloak `grafana` client secret -> kcadm.sh update inside the keycloak container (admin creds from its own env)
# Idempotent. Needs the stack up. Usage: scripts/telemetry/sync-secrets.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
envval() { grep -E "^$1=" .env | tail -1 | cut -d= -f2-; }
GPW=$(envval GRAFANA_ADMIN_PASSWORD); CS=$(envval GRAFANA_OAUTH_CLIENT_SECRET)
[ -n "$GPW" ] && [ -n "$CS" ] || { echo "FAIL .env lacks the secrets; run scripts/init-env.sh"; exit 1; }

docker exec grafana grafana cli admin reset-admin-password "$GPW" >/dev/null && echo "OK   grafana admin password synced"

KC="docker exec keycloak /opt/keycloak/bin/kcadm.sh"
KCPW=$(docker exec keycloak printenv KEYCLOAK_ADMIN_PASSWORD); KCU=$(docker exec keycloak printenv KEYCLOAK_ADMIN)
$KC config credentials --server http://localhost:8080 --realm master --user "$KCU" --password "$KCPW" >/dev/null
CID=$($KC get clients -r polaris -q clientId=grafana --fields id --format csv --noquotes | tr -d '\r')
[ -n "$CID" ] || { echo "FAIL keycloak client grafana not found"; exit 1; }
$KC update "clients/$CID" -r polaris -s "secret=$CS" && echo "OK   keycloak grafana client secret synced"
