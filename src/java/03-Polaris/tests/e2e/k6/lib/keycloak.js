import { form } from './http.js';
import { TOKEN_URL, CLIENT_ID } from './config.js';

/** Password-grant token for a user imported from docker/keycloak/polaris-realm.json. */
export function userToken(username, password) {
  const res = form(TOKEN_URL, { grant_type: 'password', client_id: CLIENT_ID, username, password });
  return res.status === 200 ? res.json('access_token') : null;
}
