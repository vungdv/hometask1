# Telemetry access, credentials and secrets (OBS-SEC-1)

Dev/reference stack (Q1 unanswered). Nothing here is configured by clicking in Grafana: roles come from Keycloak claims, datasources and dashboards are provisioned files, folder access is a declarative file applied by a compose service, and settings are environment variables in `docker-compose.override.yml`. Where Grafana OSS cannot express something, this page says so and gives the operator procedure.

## Who can do what

Login is Keycloak SSO (`https://grafana.polaris.local`). The Grafana org role is taken from the token claims (`GF_AUTH_GENERIC_OAUTH_ROLE_ATTRIBUTE_PATH`), merging the `grafana` client roles and the realm roles; the first match wins: `admin` -> Admin, `editor` -> Editor, `viewer` -> Viewer.

`GF_AUTH_GENERIC_OAUTH_ROLE_ATTRIBUTE_STRICT=true` and **there is no fallback role**: a Keycloak user with none of the three roles (for example a `shopper`) cannot log in to Grafana. (Before O7 everyone fell back to Viewer, and the realm-role clauses never matched for users without `grafana` client roles because JMESPath errors on a missing claim; both are fixed.)

| Capability | Viewer | Editor | Admin |
|:--|:--|:--|:--|
| Open dashboards in the `operations` folder | yes | yes | yes |
| Open `engineering` and `platform` folders | no | yes | yes |
| Explore (ad-hoc Loki / Tempo / Prometheus queries) | **no** (`datasources:explore` is not granted) | yes | yes |
| Edit, add or delete a datasource | no | no | no (datasources are provisioned and read-only, `editable: false`) |
| Save over a provisioned dashboard | no | no | no (`allowUiUpdates: false`) |
| Manage users, orgs, server settings | no | no (`GF_USERS_EDITORS_CAN_ADMIN=false`) | yes |

Why these settings: `GF_USERS_VIEWERS_CAN_EDIT=false` is what withholds Explore from Viewers in Grafana 13.2.1 (verified: the Viewer permission set has `datasources:query` and `datasources:read` but not `datasources:explore`); it is pinned explicitly so a default change cannot widen access. Sign-up, org creation and anonymous access are off; the session cookie is `Secure`; Gravatar and update checks are off.

## What is provisioned as code, and where

| Concern | Mechanism | File |
|:--|:--|:--|
| Role mapping, strict mode, hardening settings | Grafana env vars | `docker-compose.override.yml` (`grafana` service) |
| Datasources, read-only | Grafana provisioning | `docker/telemetry/grafana/provisioning/datasources/datasources.yml` (`editable: false`) |
| Dashboards, read-only | Grafana provisioning | `.../provisioning/dashboards/dashboards.yml` (`allowUiUpdates: false`) |
| Folder access per role | Declarative file + one-shot compose service `grafana-access-init` calling the Grafana HTTP API | `docker/telemetry/grafana/access/folder-permissions.json`, `apply-folder-permissions.py` |
| Realm roles `admin` / `editor` / `viewer` | Keycloak realm import | `docker/keycloak/realm-export.json` |

Grafana OSS has no provisioning file for folder permissions, so the HTTP API is the mechanism; `grafana-access-init` runs on every `docker compose up` once Grafana is healthy and is idempotent (it re-applies and reads back the permissions, exiting non-zero on any mismatch). Edit `folder-permissions.json` to change who sees a folder; a role not listed has no access; the Admin role always has it. Alert links to `platform` dashboards (pipeline alerts) open for Editors and Admins only, which matches who receives those alerts (platform owner).

## Known limit (Grafana OSS 13.2.1)

Per-datasource permissions (`/api/access-control/datasources/...`, "who may query Loki") are **not available**: the endpoint returns 404 on this build. A Viewer therefore **can still run a query against Loki through `POST /api/ds/query`** (dashboard panels use the same call, so it cannot be withheld from Viewers without breaking the `operations` dashboards). What O7 does restrict: the Explore UI and permission, datasource changes, folders, and dashboard edits. Closing the API-level gap needs Grafana Enterprise/Cloud datasource permissions or a separate Grafana instance for viewers; neither is in scope. `access-check.sh` prints this as an `INFO` line so it stays visible. No step in this limit is an undocumented UI action: there is nothing to click, because the control does not exist in OSS.

## Credentials and secrets

Nothing secret is committed. Two values are generated into the git-ignored `.env`:

| Variable | Used by | Meaning |
|:--|:--|:--|
| `GRAFANA_ADMIN_USER` (default `admin`), `GRAFANA_ADMIN_PASSWORD` | `grafana`, `gcx-cli`, `grafana-access-init`, check scripts | Local break-glass login form; normal users use Keycloak |
| `GRAFANA_OAUTH_CLIENT_SECRET` | `grafana` (OAuth client secret) and `keycloak` (substituted into the realm import as `${GRAFANA_OAUTH_CLIENT_SECRET}`) | Confidential client secret of the Keycloak `grafana` client |

Compose refuses to start without them (`${VAR:?...}`), so a committed default cannot creep back; `scripts/telemetry/validate-configs.sh` also fails if the old inline values (the pre-O7 client secret and default admin password) reappear in compose, the realm, scripts, README or these docs.

### Bootstrap (works out of the box)

```bash
make up            # runs scripts/init-env.sh first
# or, without make:
./scripts/init-env.sh && docker compose up -d --build
```

`scripts/init-env.sh` creates `.env` from `.env.template` if missing and fills any missing or empty secret with `openssl rand -hex 24`. It never overwrites a value that is set. To read your generated Grafana admin password: `grep GRAFANA_ADMIN_PASSWORD .env`.

### Existing volumes and rotation

Grafana reads `GF_SECURITY_ADMIN_PASSWORD` only when it creates its database, and Keycloak imports the realm only when its volume is empty. So on a stack created before O7, or after changing a value in `.env`, both stores keep the old value until synced. Step by step:

1. Run `scripts/init-env.sh` (adds missing secrets to `.env`) or edit the value in `.env` to rotate.
2. Run `scripts/telemetry/sync-secrets.sh`. It runs `grafana cli admin reset-admin-password` in the `grafana` container and updates the Keycloak `grafana` client secret with `kcadm.sh` in the `keycloak` container (Keycloak admin credentials come from that container's own environment).
3. Recreate what reads the new environment: `docker compose up -d` (recreates `grafana`, `keycloak`, `gcx-cli`).
4. Restart the gateway: `docker compose restart nginx` (it caches container addresses, see [exposure](exposure.md)).
5. Verify: `scripts/telemetry/access-check.sh` (the old password is rejected, the `.env` one works, SSO logins succeed, which also proves Grafana and Keycloak hold the same client secret).

A fresh volume (`make clean && make up`) needs none of these steps.

Not covered by O7: the Keycloak admin account (`admin` / `admin`), the Postgres passwords and the dev user passwords (`testpass`) are still dev constants committed in compose and the realm; they are out of this slice's scope (Grafana credentials and the OAuth client secret). Treat the stack as a local dev environment.

## Operator guide: give a person Grafana access

Roles live in Keycloak, not Grafana. Via the console:

1. Open `https://id.polaris.local`, sign in as the Keycloak admin, select realm **polaris**.
2. **Users** -> select the person (or **Add user**: username, email, first and last name, then **Credentials** -> set a password).
3. **Role mapping** -> **Assign role** -> filter by realm roles -> choose `viewer`, `editor` or `admin` -> **Assign**.
4. The person opens `https://grafana.polaris.local` -> **Sign in with Keycloak**. The role applies at every login (a changed role takes effect on the next login).

Via the CLI (scriptable, same effect), from the repo root:

```bash
KC="docker exec keycloak /opt/keycloak/bin/kcadm.sh"
$KC config credentials --server http://localhost:8080 --realm master --user admin --password "$(docker exec keycloak printenv KEYCLOAK_ADMIN_PASSWORD)"
$KC add-roles -r polaris --uusername <username> --rolename viewer   # or editor / admin
```

`scripts/telemetry/access-check.sh` does exactly this for throw-away users and removes them afterwards.

## Verify (live)

`scripts/telemetry/access-check.sh` performs real Keycloak logins (the OIDC authorization-code flow scripted with curl inside `polaris-net`) for throw-away users with the realm roles `viewer`, `editor` and none, then asserts the table above: Viewer and Editor get 403 on editing, adding and deleting a datasource; Viewer has no `datasources:explore`, Editor has it; Viewer sees only `operations` and gets 403 opening a `platform` dashboard; the role-less user cannot log in; the Keycloak admin is Admin; the old admin password is rejected. `validate-configs.sh` asserts the same declarations statically (CI-safe, no stack needed).
