"""
Polaris MCP Server - CLI Interface & Commands
Handles command line options for OAuth login/logout, session inspection, and direct queries.
"""

import argparse
import time
from typing import Optional, List
from .config import POLARIS_BASE_URL, KEYCLOAK_ISSUER_URL, CLIENT_ID
from .auth import (
    load_cached_tokens,
    clear_cached_tokens,
    decode_jwt_payload,
    interactive_login,
    programmatic_code_flow,
    login_direct_grant,
)
from .tools import (
    tool_search_available_products,
    tool_get_product_by_sku,
)
from .server import run_stdio
from .telemetry import shutdown_telemetry


def main(argv: Optional[List[str]] = None) -> None:
    parser = argparse.ArgumentParser(description="Polaris MCP Server & OAuth 2.0 CLI Client")
    parser.add_argument("--login", action="store_true", help="Authenticate with Polaris via OAuth 2.0 Code Flow")
    parser.add_argument("--logout", action="store_true", help="Clear cached tokens and logout")
    parser.add_argument("--status", action="store_true", help="Display current session and token status")
    parser.add_argument("--username", type=str, help="Username for non-interactive / programmatic login")
    parser.add_argument("--password", type=str, help="Password for non-interactive / programmatic login")
    parser.add_argument("--test-search", type=str, help="Run a test search query against Polaris and print results")
    parser.add_argument("--test-sku", type=str, help="Lookup a product by SKU and print results")
    parser.add_argument("--category", type=str, help="Category filter for test search")
    parser.add_argument("--available-only", action="store_true", default=True, help="Filter for in-stock items")
    args = parser.parse_args(argv)

    try:
        if args.logout:
            clear_cached_tokens()
            print("Logged out. Token cache cleared.")
            return

        if args.status:
            tokens = load_cached_tokens()
            if not tokens or not tokens.get("access_token"):
                print("Status: Not authenticated. (No active token found in cache)")
                return
            payload = decode_jwt_payload(tokens.get("access_token", ""))
            expires_at = tokens.get("expires_at", 0)
            remaining = max(0, int(expires_at - time.time()))
            print("Session Status: Authenticated")
            print(f"- User: {payload.get('preferred_username', 'Unknown')}")
            print(f"- Email: {payload.get('email', 'N/A')}")
            print(f"- Client ID: {CLIENT_ID}")
            print(f"- Issuer: {KEYCLOAK_ISSUER_URL}")
            print(f"- Access Token Remaining TTL: {remaining} seconds")
            print(f"- Has Refresh Token: {'Yes' if bool(tokens.get('refresh_token')) else 'No'}")
            return

        if args.login:
            if args.username and args.password:
                print(f"Authenticating via Programmatic Code Flow with PKCE for user '{args.username}'...")
                try:
                    tokens = programmatic_code_flow(args.username, args.password)
                    print(f"Login successful! Cached tokens for '{args.username}'.")
                except Exception as e:
                    print(f"Code flow failed ({e}), falling back to direct grant...")
                    tokens = login_direct_grant(args.username, args.password)
                    print(f"Direct grant login successful! Cached tokens for '{args.username}'.")
            else:
                tokens = interactive_login()
                payload = decode_jwt_payload(tokens.get("access_token", ""))
                print(f"Login successful as '{payload.get('preferred_username', 'user')}'.")
            return

        if args.test_search is not None:
            print(f"Connecting to {POLARIS_BASE_URL}...")
            print(tool_search_available_products(
                query=args.test_search,
                category=args.category,
                available_only=args.available_only,
            ))
        elif args.test_sku is not None:
            print(f"Connecting to {POLARIS_BASE_URL}...")
            print(tool_get_product_by_sku(args.test_sku))
        else:
            run_stdio()
    finally:
        shutdown_telemetry()
