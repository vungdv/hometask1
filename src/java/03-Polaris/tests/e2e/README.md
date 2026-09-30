# Polaris End-to-End Testing (Playwright CLI)

This directory houses E2E verification recipes, session proofs, and automation specs.

## Commands
```bash
# Open Swagger UI in Playwright CLI
make playwright-ui

# Run Playwright verification recipes
playwright-cli open https://polaris.local/chat
```

## Fulfilment end-to-end (PRD-007 Scenarios 2, 4-7) - k6
```bash
make up && make e2e-fulfilment
```
API-first k6 scenario (`k6/fulfilment.js`, helpers in `k6/lib/`), run through the `grafana/k6` image like `make test-perf`.
Repeatable: every run passes without cleanup.

1. **Setup (idempotent)** - Keycloak Admin REST API on the `polaris` realm: create-if-absent shopper `alice.tran` (verified email
   linking to the seeded customer, realm role `shopper`) and staff `e2e.staff` (realm role `admin`); existing users are left alone,
   409s tolerated. Then via the catalog REST API: create-if-absent product `E2E-UNLIMITED-01` with ~2^31 stock and top it up
   (`PUT /products/sku/{sku}/inventory`) if a run ever lowered it. Fails fast if the realm has no `polaris-fulfilment-emulator` client
   (realm predates F1 - reset the Keycloak volume).
2. **Scenario** - OIDC password-grant token, place 1 order + a batch of 10 (fresh `Idempotency-Key` per run), poll
   `GET /api/v1/orders/{n}` until `DELIVERED` with `assignedPartner` (or `TIMEOUT`), assert the batch has more than one winning partner.
   k6 thresholds/checks decide pass/fail.
3. **Kafka events** - k6 has no native Kafka client, so `run-fulfilment.sh` feeds the order numbers the run logged (`E2E_ORDERS ...`)
   to `verify-kafka-events.sh`, which reads `polaris.order.lifecycle` with `kafka-console-consumer` inside `kafka-1` and checks the five
   `order.*.v1` events per order, in order, and that the `confirmed` event's `assignedPartner` equals the partner `GET /orders/{n}` returned. This avoids building a custom xk6-kafka binary; it is still a single command.

Local-dev-only defaults: shopper `alice.tran` and staff `e2e.staff` (password `testpass`), Keycloak `admin/admin` and the emulator's
dev client secret are defaults for the local compose stack only. They are env-overridable (`E2E_USER`, `E2E_PASSWORD`, `E2E_STAFF_*`, `KC_ADMIN_*`) and must
never be reused outside local development.

Tunables (env): `KC_ADMIN_USER`, `KC_ADMIN_PASSWORD`, `E2E_STAFF_USER`, `E2E_STAFF_PASSWORD`, `BATCH`, `TIMEOUT`, `SKU`, `POLL_INTERVAL`, `E2E_USER`, `E2E_PASSWORD`, `E2E_EMAIL`, `API_BASE`, `KC_BASE`.
Extra arguments after `run-fulfilment.sh` go to `k6 run`. Requires the emulator container running and a realm that includes its client.
