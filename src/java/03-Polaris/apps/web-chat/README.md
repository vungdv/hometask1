# Polaris Web Chat Client

Lightweight, responsive chat client for the Polaris AI Assistant.

## Features
- Clean conversational UI for interacting with Polaris Assistant.
- Automatic session generation and continuity via session storage.
- Typing indicators and real-time response rendering.
- New Chat reset action.
- Direct integration with `POST /api/v1/assistant/chat` which forwards user messages to the Foundation AI Model.

## Integration
These static assets are packaged into `apps/polaris-server` under `/static/chat` and accessible at `/chat` (or `https://polaris.local/chat`).
