# Polaris Web Chat Client

This is the standalone web chat client for Polaris Enterprise Assistant.

## Features
- Keycloak Authorization Code Flow with PKCE (RFC 7636, S256).
- Server-Sent Events (SSE) streaming reader (`POST /api/v1/assistant/sessions/{sessionId}/messages`).
- Interactive widgets:
  - Product exploration cards
  - Order staging draft cards with 15-minute countdown
  - Order confirmation cards
  - RFC 7807 problem diagnostic cards with clickable remedy buttons
- WCAG 2.1 AA accessible UI.

## Integration
In production, these assets are bundled into the Polaris backend server artifact (`apps/polaris-server`) under `/static/chat` and served at `https://polaris.local/chat`.
