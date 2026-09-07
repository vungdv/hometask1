"""
Polaris MCP Server - Authentication Subsystem
Manages OAuth 2.0 PKCE tokens, lifecycle caching, and access token resolution.
"""

import time
from typing import Dict, Any
from ..config import FALLBACK_BEARER_TOKEN
from .token_cache import (
    load_cached_tokens,
    save_cached_tokens,
    clear_cached_tokens,
)
from .pkce import generate_pkce_pair, decode_jwt_payload
from .oauth_client import (
    exchange_code_for_tokens,
    refresh_access_token,
    login_direct_grant,
)
from .callback_server import (
    interactive_login,
    programmatic_code_flow,
)
from ..telemetry import get_logger

logger = get_logger("polaris_mcp.auth")


def get_access_token() -> str:
    """
    Retrieves a valid access token:
    1. Returns unexpired cached token if present (with 30s buffer)
    2. Refreshes token using refresh_token if expired
    3. Falls back to static POLARIS_BEARER_TOKEN if configured
    4. Raises RuntimeError if unauthenticated
    """
    tokens = load_cached_tokens()
    current_time = time.time()

    # 1. Valid cached access token
    access_token = tokens.get("access_token")
    expires_at = tokens.get("expires_at", 0)
    if access_token and current_time < (expires_at - 30):
        return access_token

    # 2. Try refreshing token
    refresh_token = tokens.get("refresh_token")
    if refresh_token:
        try:
            new_tokens = refresh_access_token(refresh_token)
            return new_tokens["access_token"]
        except Exception as e:
            logger.warning("Token refresh failed; re-authentication may be required", error=str(e))

    # 3. Fallback to static token if specified
    if FALLBACK_BEARER_TOKEN:
        return FALLBACK_BEARER_TOKEN

    raise RuntimeError(
        "Authentication required: No active session. Please authenticate by running:\n"
        "  python3 mcp/mcp_polaris_products.py --login\n"
        "or provide POLARIS_BEARER_TOKEN in environment."
    )


__all__ = [
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
]
