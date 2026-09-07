"""
Polaris MCP Server - OAuth 2.0 Client (RFC 6749 & RFC 7636)
Handles token exchange, refresh, and direct grants against Keycloak.
Instrumented with OpenTelemetry spans and metrics.
"""

import json
import urllib.request
import urllib.parse
from typing import Dict, Any
from ..config import (
    KEYCLOAK_ISSUER_URL,
    CLIENT_ID,
    REDIRECT_URI,
    AUTH_SCOPE,
    SSL_CONTEXT,
)
from .token_cache import save_cached_tokens
from ..telemetry import get_tracer, get_meter, get_logger, SpanKind

tracer = get_tracer()
meter = get_meter()
logger = get_logger("polaris_mcp.auth.oauth_client")

refresh_counter = meter.create_counter(
    "mcp_oauth_token_refreshes_total",
    unit="1",
    description="Total OAuth token refresh attempts",
)


def exchange_code_for_tokens(code: str, code_verifier: str) -> Dict[str, Any]:
    """Exchanges authorization code and PKCE verifier for access & refresh tokens."""
    token_url = f"{KEYCLOAK_ISSUER_URL.rstrip('/')}/protocol/openid-connect/token"
    payload = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "client_id": CLIENT_ID,
        "code": code,
        "redirect_uri": REDIRECT_URI,
        "code_verifier": code_verifier,
    }).encode("utf-8")

    req = urllib.request.Request(
        token_url,
        data=payload,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
        },
    )

    with tracer.start_as_current_span("oauth.exchange_code", kind=SpanKind.CLIENT) as span:
        span.set_attribute("oauth.token_endpoint", token_url)
        span.set_attribute("oauth.client_id", CLIENT_ID)
        with urllib.request.urlopen(req, context=SSL_CONTEXT, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            save_cached_tokens(data)
            logger.info("Successfully exchanged authorization code for tokens")
            return data


def refresh_access_token(refresh_token: str) -> Dict[str, Any]:
    """Renews access token using a refresh token."""
    token_url = f"{KEYCLOAK_ISSUER_URL.rstrip('/')}/protocol/openid-connect/token"
    payload = urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "client_id": CLIENT_ID,
        "refresh_token": refresh_token,
    }).encode("utf-8")

    req = urllib.request.Request(
        token_url,
        data=payload,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
        },
    )

    with tracer.start_as_current_span("oauth.refresh_token", kind=SpanKind.CLIENT) as span:
        span.set_attribute("oauth.token_endpoint", token_url)
        span.set_attribute("oauth.client_id", CLIENT_ID)
        try:
            with urllib.request.urlopen(req, context=SSL_CONTEXT, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if "refresh_token" not in data:
                    data["refresh_token"] = refresh_token
                save_cached_tokens(data)
                refresh_counter.add(1, {"result": "success"})
                logger.info("Successfully refreshed access token")
                return data
        except Exception as e:
            refresh_counter.add(1, {"result": "failure"})
            logger.warning("Token refresh failed", error=str(e))
            raise


def login_direct_grant(username: str, password: str) -> Dict[str, Any]:
    """Direct grant login for automated testing or headless environments."""
    token_url = f"{KEYCLOAK_ISSUER_URL.rstrip('/')}/protocol/openid-connect/token"
    payload = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": CLIENT_ID,
        "username": username,
        "password": password,
        "scope": AUTH_SCOPE,
    }).encode("utf-8")

    req = urllib.request.Request(
        token_url,
        data=payload,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
        },
    )

    with tracer.start_as_current_span("oauth.direct_grant", kind=SpanKind.CLIENT) as span:
        span.set_attribute("oauth.token_endpoint", token_url)
        span.set_attribute("oauth.client_id", CLIENT_ID)
        span.set_attribute("oauth.username", username)
        with urllib.request.urlopen(req, context=SSL_CONTEXT, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            save_cached_tokens(data)
            logger.info("Successfully authenticated via direct grant", username=username)
            return data
