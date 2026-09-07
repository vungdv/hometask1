"""
Polaris MCP Server - Token Cache Management
Handles token caching in memory and persistent atomic disk storage.
"""

import os
import json
import time
from typing import Dict, Any
from ..config import TOKEN_CACHE_FILE
from ..telemetry import get_logger

logger = get_logger("polaris_mcp.auth.token_cache")

_memory_tokens: Dict[str, Any] = {}


def load_cached_tokens() -> Dict[str, Any]:
    """Loads tokens from memory or persistent disk cache."""
    global _memory_tokens
    if _memory_tokens:
        return _memory_tokens
    if os.path.exists(TOKEN_CACHE_FILE):
        try:
            with open(TOKEN_CACHE_FILE, "r") as f:
                _memory_tokens = json.load(f)
                return _memory_tokens
        except Exception as e:
            logger.warning("Failed to read token cache", error=str(e), path=TOKEN_CACHE_FILE)
    return {}


def save_cached_tokens(token_data: Dict[str, Any]) -> None:
    """Saves tokens to memory and persistent disk cache with expiration metadata."""
    global _memory_tokens
    _memory_tokens = token_data.copy()
    if "expires_in" in token_data and "expires_at" not in token_data:
        _memory_tokens["expires_at"] = time.time() + float(token_data["expires_in"])
    try:
        cache_dir = os.path.dirname(os.path.abspath(TOKEN_CACHE_FILE))
        os.makedirs(cache_dir, exist_ok=True)
        # Write atomically via temp file to prevent corrupted partial reads
        temp_file = f"{TOKEN_CACHE_FILE}.tmp"
        with open(temp_file, "w") as f:
            json.dump(_memory_tokens, f, indent=2)
        os.replace(temp_file, TOKEN_CACHE_FILE)
    except Exception as e:
        logger.warning("Could not save token cache", error=str(e), path=TOKEN_CACHE_FILE)


def clear_cached_tokens() -> None:
    """Clears tokens from memory and deletes disk cache file."""
    global _memory_tokens
    _memory_tokens = {}
    if os.path.exists(TOKEN_CACHE_FILE):
        try:
            os.remove(TOKEN_CACHE_FILE)
        except Exception:
            pass
