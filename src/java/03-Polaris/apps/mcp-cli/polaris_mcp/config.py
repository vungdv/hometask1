"""
Polaris MCP Server - Configuration Module
12-Factor Configuration as Data sourced from environment variables with sensible defaults.
"""

import os
import ssl

# -----------------------------------------------------------------------------
# Polaris Backend & OAuth Configuration
# -----------------------------------------------------------------------------
POLARIS_BASE_URL = os.environ.get("POLARIS_API_URL", "https://polaris.local")
KEYCLOAK_ISSUER_URL = os.environ.get(
    "POLARIS_ISSUER_URL", "https://id.polaris.local/realms/polaris"
)
CLIENT_ID = os.environ.get("POLARIS_CLIENT_ID", "polaris-mcp")
CALLBACK_HOST = os.environ.get("POLARIS_CALLBACK_HOST", "127.0.0.1")
CALLBACK_PORT = int(os.environ.get("POLARIS_CALLBACK_PORT", "8085"))
REDIRECT_URI = os.environ.get(
    "POLARIS_REDIRECT_URI", f"http://{CALLBACK_HOST}:{CALLBACK_PORT}/oauth/callback"
)
AUTH_SCOPE = os.environ.get("POLARIS_AUTH_SCOPE", "openid profile email offline_access")
TOKEN_CACHE_FILE = os.environ.get(
    "POLARIS_TOKEN_CACHE_FILE",
    os.path.expanduser("~/.polaris_mcp_tokens.json")
)
FALLBACK_BEARER_TOKEN = os.environ.get("POLARIS_BEARER_TOKEN", "")

# -----------------------------------------------------------------------------
# TLS / SSL Configuration
# -----------------------------------------------------------------------------
SSL_CONTEXT = ssl.create_default_context()
if os.environ.get("POLARIS_INSECURE_TLS", "true").lower() in ("true", "1", "yes"):
    SSL_CONTEXT.check_hostname = False
    SSL_CONTEXT.verify_mode = ssl.CERT_NONE

# -----------------------------------------------------------------------------
# OpenTelemetry Observability Configuration
# -----------------------------------------------------------------------------
OTEL_SERVICE_NAME = os.environ.get("OTEL_SERVICE_NAME", "polaris-mcp-server")
OTEL_SERVICE_VERSION = "1.0.0"
OTEL_EXPORTER_OTLP_ENDPOINT = os.environ.get(
    "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318"
).rstrip("/")
OTEL_SDK_DISABLED = os.environ.get("OTEL_SDK_DISABLED", "false").lower() in ("true", "1", "yes")
OTEL_LOG_LEVEL = os.environ.get("OTEL_LOG_LEVEL", "INFO").upper()
