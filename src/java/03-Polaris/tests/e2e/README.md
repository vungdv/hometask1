# Polaris End-to-End Testing (Playwright CLI)

This directory houses E2E verification recipes, session proofs, and automation specs.

## Commands
```bash
# Open Swagger UI in Playwright CLI
make playwright-ui

# Run Playwright verification recipes
playwright-cli open https://polaris.local/chat
```

## Fulfilment end-to-end (PRD-007 Scenarios 2, 4-7)
```bash
make up && make e2e-fulfilment
```
Places 1 + 10 orders as `alice.tran` (OIDC password grant, REST), waits for every order to reach `DELIVERED` with an assigned partner,
reads `polaris.order.lifecycle` from Kafka to check all 5 `order.*.v1` events per order in order, and checks the batch has more than
one winning partner. Prints `PASS`/`FAIL` and exits non-zero on failure. Tunables: `BATCH`, `TIMEOUT`, `SKU`, `API_BASE`, `TOKEN_URL`.
Requires `jq`, and a Keycloak realm imported with the emulator client (`make clean && make up` on a stack from before F1).
