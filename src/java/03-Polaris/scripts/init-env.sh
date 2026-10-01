#!/usr/bin/env bash
# Bootstraps .env for local dev (plan O7): creates it from .env.template if missing and fills in any missing or empty
# generated secret. Idempotent; NEVER overwrites a value that is already set. Run by `make up`.
#   GRAFANA_ADMIN_PASSWORD        Grafana local admin (break-glass login form; normal users sign in with Keycloak)
#   GRAFANA_OAUTH_CLIENT_SECRET   Confidential client secret shared by Grafana and the Keycloak `grafana` client
# Rotating: set the value in .env, then run scripts/telemetry/sync-secrets.sh to push it into existing volumes.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { cp .env.template .env; echo "created .env from .env.template"; }
gen() { openssl rand -hex 24; }
for k in GRAFANA_ADMIN_PASSWORD GRAFANA_OAUTH_CLIENT_SECRET; do
  if ! grep -qE "^${k}=.+" .env; then
    grep -qE "^${k}=" .env && python3 - "$k" <<'PY'
import re, sys
k = sys.argv[1]
t = open(".env").read()
open(".env", "w").write(re.sub(rf"^{k}=.*\n?", "", t, flags=re.M))
PY
    [ -n "$(tail -c1 .env)" ] && echo >> .env
    echo "${k}=$(gen)" >> .env
    echo "generated ${k} in .env"
  fi
done
