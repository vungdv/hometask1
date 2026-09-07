"""
Polaris MCP - Model Context Protocol Server for Polaris Product Catalog
Decomposed modular package conforming to Polaris Architecture Principles (AGENTS.md).
"""

__version__ = "1.0.0"

from .config import (
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
)
from .auth import (
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
)
from .client import http_get
from .tools import (
    TOOLS,
    execute_tool,
    tool_search_available_products,
    tool_get_product_by_sku,
)
from .server import handle_json_rpc, run_stdio
from .cli import main
from .telemetry import get_tracer, get_meter, get_logger, shutdown_telemetry

__all__ = [
    "__version__",
    "POLARIS_BASE_URL",
    "KEYCLOAK_ISSUER_URL",
    "CLIENT_ID",
    "CALLBACK_HOST",
    "CALLBACK_PORT",
    "REDIRECT_URI",
    "AUTH_SCOPE",
    "TOKEN_CACHE_FILE",
    "FALLBACK_BEARER_TOKEN",
    "SSL_CONTEXT",
    "OTEL_SERVICE_NAME",
    "OTEL_EXPORTER_OTLP_ENDPOINT",
    "get_access_token",
    "load_cached_tokens",
    "save_cached_tokens",
    "clear_cached_tokens",
    "generate_pkce_pair",
    "decode_jwt_payload",
    "exchange_code_for_tokens",
    "refresh_access_token",
    "login_direct_grant",
    "interactive_login",
    "programmatic_code_flow",
    "http_get",
    "TOOLS",
    "execute_tool",
    "tool_search_available_products",
    "tool_get_product_by_sku",
    "handle_json_rpc",
    "run_stdio",
    "main",
    "get_tracer",
    "get_meter",
    "get_logger",
    "shutdown_telemetry",
]
