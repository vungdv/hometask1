#!/usr/bin/env python3
"""
Polaris MCP Server for Product Search & Management
Exposes product catalog search and detail lookup tools via the Model Context Protocol (MCP) JSON-RPC 2.0.

Adheres to Polaris Architecture Principles (AGENTS.md):
- RFC 9110 HTTP semantics
- RFC 6749 OAuth 2.0 Authorization Code Grant
- RFC 7636 Proof Key for Code Exchange (PKCE S256)
- RFC 8252 OAuth 2.0 for Native Apps (loopback redirect URIs)
- OpenTelemetry Distributed Tracing & W3C traceparent propagation
- OpenTelemetry Metrics & Stderr-safe Structured Logging
- 12-Factor Configuration as Data

This file serves as the executable entrypoint and facade for the modular `polaris_mcp` package.
"""

import sys
import os

# Ensure the package root is in sys.path when executed directly as a script
_CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
if _CURRENT_DIR not in sys.path:
    sys.path.insert(0, _CURRENT_DIR)

from polaris_mcp import (
    POLARIS_BASE_URL,
    KEYCLOAK_ISSUER_URL,
    CLIENT_ID,
    CALLBACK_HOST,
    CALLBACK_PORT,
    REDIRECT_URI,
    AUTH_SCOPE,
    TOKEN_CACHE_FILE,
    FALLBACK_BEARER_TOKEN,
    SSL_CONTEXT,
    OTEL_SERVICE_NAME,
    OTEL_EXPORTER_OTLP_ENDPOINT,
    get_access_token,
    load_cached_tokens,
    save_cached_tokens,
    clear_cached_tokens,
    generate_pkce_pair,
    decode_jwt_payload,
    exchange_code_for_tokens,
    refresh_access_token,
    login_direct_grant,
    interactive_login,
    programmatic_code_flow,
    http_get,
    TOOLS,
    execute_tool,
    tool_search_available_products,
    tool_get_product_by_sku,
    handle_json_rpc,
    run_stdio,
    main,
    get_tracer,
    get_meter,
    get_logger,
)

if __name__ == "__main__":
    main()
