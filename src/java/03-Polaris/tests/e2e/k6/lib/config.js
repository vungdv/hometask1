// Environment-driven configuration for the fulfilment e2e (12-factor: nothing hardcoded but local-dev defaults).
export const API_BASE = __ENV.API_BASE || 'http://host.docker.internal:8080';
export const KC_BASE = __ENV.KC_BASE || 'https://id.polaris.local';
export const REALM = __ENV.REALM || 'polaris';
export const TOKEN_URL = `${KC_BASE}/realms/${REALM}/protocol/openid-connect/token`;

// Both users are imported from docker/keycloak/polaris-realm.json; a stale realm is fixed with `make clean && make up`.
// Shopper: linked to a seeded customer (V12 migration / GET /customers/me).
export const SHOPPER = {
  username: __ENV.E2E_USER || 'alice.tran',
  password: __ENV.E2E_PASSWORD || 'testpass',
};
// Staff account used only to prepare the catalog (catalog.write + inventory.write via the admin realm role).
export const STAFF = {
  username: __ENV.E2E_STAFF_USER || 'testuser',
  password: __ENV.E2E_STAFF_PASSWORD || 'testpass',
};

export const CLIENT_ID = __ENV.CLIENT_ID || 'polaris-app';

export const SKU = __ENV.SKU || 'E2E-UNLIMITED-01';
export const MIN_STOCK = Number(__ENV.MIN_STOCK || 1000000000); // "unlimited": top up below this, never depletes in practice
export const BATCH = Number(__ENV.BATCH || 10);
export const TIMEOUT_S = Number(__ENV.TIMEOUT || 120);          // whole fulfilment of all orders
export const POLL_INTERVAL_S = Number(__ENV.POLL_INTERVAL || 2);
