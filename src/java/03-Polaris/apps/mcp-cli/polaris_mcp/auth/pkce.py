"""
Polaris MCP Server - OAuth 2.0 PKCE Helpers (RFC 7636)
Generates cryptographically secure PKCE verifiers/challenges and decodes JWT payloads.
"""

import base64
import hashlib
import json
import secrets
from typing import Tuple, Dict, Any


def generate_pkce_pair() -> Tuple[str, str]:
    """
    Generates a cryptographically random code_verifier (43-128 chars)
    and its SHA-256 base64url-encoded code_challenge (S256).
    """
    verifier = secrets.token_urlsafe(64)[:128]
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return verifier, challenge


def decode_jwt_payload(jwt_token: str) -> Dict[str, Any]:
    """Extracts claims from unencrypted JWT token payload without signature verification."""
    try:
        parts = jwt_token.split(".")
        if len(parts) >= 2:
            payload_b64 = parts[1]
            padded = payload_b64 + "=" * ((4 - len(payload_b64) % 4) % 4)
            decoded = base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8")
            return json.loads(decoded)
    except Exception:
        pass
    return {}
