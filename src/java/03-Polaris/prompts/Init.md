# Step-1: 

Manually setup the architecture use docker compose. 

# Step-2:
Use agy to document into README.md

# Step-3:
Define principles: simplicity, beneath surface and follow standard, workable business boundary for both business and operator.

# Step-4:
Plan to build a simple AI Agent to search for avaiable products.

# Step-5:
Build an MCP Server & choose authentication protocol
@[mcp/mcp_polaris_products.py] this one use an environment configuration token. tell me about standard ways like secret client, auth2 code flow, ...
Results: 

 ### Comparison Matrix

 Flow / Pattern                       | Actor / Identity                    | User Prompt Required?               | Refresh Mechanism                           | MCP Suitability
  --------------------------------------|-------------------------------------|-------------------------------------|---------------------------------------------|------------------------------------------------
   Static Bearer Token (Current)        | Pre-minted                          | No                                  | Manual restart / re-export                  | Local dev only
   Client Credentials                   | Service Account (M2M)               | No                                  | Auto-fetch before expiry using secret / key | Highest for autonomous tool servers
   Auth Code + PKCE                     | Human User                          | Yes (Browser login)                 | refresh_token rotation                      | Best when user-scoped permissions are required
   Device Authorization                 | Human User                          | Yes (Browser code entry)            | refresh_token rotation                      | Great for headless CLIs without loopback HTTP
   Host Delegation                      | Host / Caller                       | Managed by Host                     | Host manages lifecycle                      | Future-proof for MCP protocol clients
  ──────

 ### 1. OAuth 2.0 Client Credentials Grant (Service Account / "Secret Client") — RFC 6749 §4.4

  │ Best fit for backend MCP daemons performing autonomous tasks (e.g., searching catalogs, querying telemetry).

  • Identity: Machine-to-Machine (M2M). The MCP server acts as its own service principal, not on behalf of a human user.
  • Credentials: Sourced from environment configuration:
      • POLARIS_CLIENT_ID (e.g., polaris-mcp-service)
      • POLARIS_CLIENT_SECRET (confidential client secret)
      • POLARIS_TOKEN_URL (e.g., https://id.polaris.local/realms/polaris/protocol/openid-connect/token)
  • How it works:
      1. The MCP script requests an access token directly from Keycloak via POST /protocol/openid-connect/token with grant_type=client_credentials.
      2. Keycloak responds with a signed JWT (access_token) and expires_in (e.g., 300 seconds).
      3. The MCP server caches the token in memory and automatically fetches a new one before expiry (e.g., when remaining TTL < 30s).
  • Enterprise variation (RFC 7523 / RFC 8705): Instead of a shared client secret, the client can use Private Key JWT (client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer) or Mutual TLS
  (mTLS) for secretless machine authentication.
  ──────
  ### 2. OAuth 2.0 Authorization Code Flow with PKCE — RFC 6749 §4.1 + RFC 7636
  │ Best fit when the MCP server must act on behalf of a specific human user (e.g., viewing user carts, creating orders).
  • Identity: User-delegated access. The token bears the user's claims (sub, roles, email).
  • How it works:
      1. The client generates a high-entropy code_verifier and SHA-256 code_challenge (PKCE).
      2. The client opens a browser or system dialog pointing to Keycloak's authorization endpoint (https://id.polaris.local/realms/polaris/protocol/openid-connect/auth).
      3. The user logs in (e.g., testuser / testpass).
      4. Keycloak redirects back to a local loopback callback (e.g., http://127.0.0.1:8089/callback) with an authorization code.
      5. The client exchanges the authorization code and code_verifier for an access_token and a refresh_token.
      6. Subsequent calls automatically use the refresh_token (grant_type=refresh_token) to obtain fresh access tokens without prompting the user again.
  • Consideration for MCP: MCP servers run via stdio or SSE. Spawning an interactive browser login inside a background stdio process requires running a transient local HTTP callback server or prompting the user.
  ──────
  ### 3. OAuth 2.0 Device Authorization Grant (Device Flow) — RFC 8628
  │ Best fit for CLI / terminal-driven tools without a local redirect listener.
  • Identity: User-delegated access.
  • How it works:
      1. The MCP script calls POST /protocol/openid-connect/auth/device on Keycloak.
      2. Keycloak returns a verification_uri and a user code (e.g., https://id.polaris.local/device?user_code=ABCD-1234).
      3. The script displays this URL in the logs/console.
      4. The script polls the token endpoint in the background while the user approves the prompt in their browser.
      5. Once approved, Keycloak returns the access_token and refresh_token.

  ──────
  ### 4. Host-Delegated Token Injection (MCP Protocol Standard)

  │ Best architectural alignment for MCP ecosystems (Claude Desktop, IDEs, AGY).

  In standard MCP production designs, the MCP Host (the AI IDE or platform client) owns the user session and authentication:

  1. The host negotiates the OAuth 2.0 / OIDC flow with the IdP.
  2. The host passes the authorization bearer token down to the MCP server either:
      • In the session handshake initialization, or
      • Per tool call / request headers (for SSE/HTTP transports).
  3. The tool server remains stateless and simply propagates the caller's token down to SecurityConfig.java:37.
  ──────


  Choosed ### 2. OAuth 2.0 Authorization Code Flow with PKCE — RFC 6749 §4.1 + RFC 7636 for "Best fit when the MCP server must act on behalf of a specific human user (e.g., viewing user carts, creating orders)."