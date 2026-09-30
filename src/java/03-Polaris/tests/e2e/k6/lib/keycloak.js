import http from 'k6/http';
import { check, fail } from 'k6';
import {
  ADMIN_URL, KC_BASE, KC_ADMIN_USER, KC_ADMIN_PASSWORD, TOKEN_URL, CLIENT_ID, EMULATOR_CLIENT_ID,
} from './config.js';
import { form, json } from './http.js';

/** Password-grant token for a realm user (what a real client obtains through OIDC). */
export function userToken(username, password) {
  const res = form(TOKEN_URL, { grant_type: 'password', client_id: CLIENT_ID, username, password });
  return res.status === 200 ? res.json('access_token') : null;
}

function adminToken() {
  const res = form(`${KC_BASE}/realms/master/protocol/openid-connect/token`, {
    grant_type: 'password', client_id: 'admin-cli', username: KC_ADMIN_USER, password: KC_ADMIN_PASSWORD,
  });
  if (res.status !== 200) fail(`Keycloak admin token failed: ${res.status} ${res.body}`);
  return res.json('access_token');
}

function findUserId(auth, username) {
  const res = http.get(`${ADMIN_URL}/users?username=${encodeURIComponent(username)}&exact=true`, auth);
  if (res.status !== 200) fail(`Keycloak user lookup failed: ${res.status} ${res.body}`);
  const users = res.json();
  return users.length ? users[0].id : null;
}

/**
 * Create-if-absent (idempotent): user with a permanent password, a verified email (Polaris links the
 * customer by verified email on first use) and the given realm roles. Safe to run any number of times;
 * an existing user is left as is except that missing realm roles are (re)assigned.
 */
export function ensureUser(auth, u) {
  let id = findUserId(auth, u.username);
  if (!id) {
    const res = http.post(`${ADMIN_URL}/users`, JSON.stringify({
      username: u.username, email: u.email, firstName: u.firstName, lastName: u.lastName,
      enabled: true, emailVerified: true,
      credentials: [{ type: 'password', value: u.password, temporary: false }],
    }), auth);
    // 409: a parallel run created it between lookup and create - same end state.
    if (res.status !== 201 && res.status !== 409) fail(`create user ${u.username}: ${res.status} ${res.body}`);
    id = findUserId(auth, u.username);
  }
  const roles = u.realmRoles.map((name) => {
    const r = http.get(`${ADMIN_URL}/roles/${name}`, auth);
    if (r.status !== 200) fail(`realm role '${name}' not found (${r.status}); is the polaris realm imported?`);
    return r.json();
  });
  const map = http.post(`${ADMIN_URL}/users/${id}/role-mappings/realm`, JSON.stringify(roles), auth);
  check(map, { [`roles assigned to ${u.username}`]: (r) => r.status === 204 });
  return id;
}

/** Idempotent setup of all e2e users through the Keycloak Admin REST API. */
export function ensureUsers(users) {
  const auth = json(adminToken());
  users.forEach((u) => ensureUser(auth, u));
  return auth;
}

/** The emulator's service-account client must exist in the realm (imported by F1); this only verifies it. */
export function emulatorClientPresent(auth) {
  const res = http.get(`${ADMIN_URL}/clients?clientId=${EMULATOR_CLIENT_ID}`, auth);
  return res.status === 200 && res.json().length > 0;
}
