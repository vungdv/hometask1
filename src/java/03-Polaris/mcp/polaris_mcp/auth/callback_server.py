"""
Polaris MCP Server - OAuth 2.0 Loopback Callback Server (RFC 8252)
Implements native app authorization code grant with PKCE (RFC 7636).
Supports both interactive browser login and programmatic automated flows.
"""

import re
import secrets
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Optional, Dict, Any
from ..config import (
    KEYCLOAK_ISSUER_URL,
    CLIENT_ID,
    CALLBACK_HOST,
    CALLBACK_PORT,
    REDIRECT_URI,
    AUTH_SCOPE,
    SSL_CONTEXT,
)
from .pkce import generate_pkce_pair
from .oauth_client import exchange_code_for_tokens
from ..telemetry import get_tracer, get_logger, SpanKind

tracer = get_tracer()
logger = get_logger("polaris_mcp.auth.callback_server")


class _CallbackState:
    expected_state: Optional[str] = None
    received_code: Optional[str] = None
    auth_error: Optional[str] = None
    completion_event: threading.Event = threading.Event()


class OAuthCallbackHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Silence access logs to preserve clean stdio JSON-RPC / CLI output
        pass

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/oauth/callback":
            params = urllib.parse.parse_qs(parsed.query)
            state = params.get("state", [None])[0]

            if state != _CallbackState.expected_state:
                _CallbackState.auth_error = (
                    f"State mismatch: expected {_CallbackState.expected_state}, got {state}"
                )
                self.send_response(400)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(b"<html><body><h2>Authentication Error: State mismatch</h2></body></html>")
            elif "error" in params:
                err_msg = params.get("error_description", params.get("error"))[0]
                _CallbackState.auth_error = err_msg
                self.send_response(400)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(
                    f"<html><body><h2>Authentication Failed</h2><p>{err_msg}</p></body></html>".encode("utf-8")
                )
            else:
                _CallbackState.received_code = params.get("code", [None])[0]
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(
                    b"<!DOCTYPE html><html><head><title>Polaris Authentication</title></head>"
                    b"<body style=\"font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; text-align: center; padding-top: 60px; background-color: #f8fafc;\">"
                    b"<div style=\"display: inline-block; padding: 40px; border-radius: 12px; background: white; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); max-width: 480px;\">"
                    b"<h2 style=\"color: #16a34a; margin-top: 0;\">&#10004; Authentication Successful</h2>"
                    b"<p style=\"color: #475569; font-size: 15px; line-height: 1.5;\">Polaris OAuth 2.0 Authorization Code flow completed.<br>You may close this tab and return to your terminal / AI assistant.</p>"
                    b"</div></body></html>"
                )
            _CallbackState.completion_event.set()
        else:
            self.send_response(404)
            self.end_headers()


def interactive_login(timeout: int = 120) -> Dict[str, Any]:
    """
    Executes OAuth 2.0 Authorization Code flow with PKCE (S256):
    1. Starts a temporary HTTP loopback listener on 127.0.0.1:CALLBACK_PORT
    2. Opens the browser to Keycloak authorization endpoint
    3. Captures authorization code on redirect
    4. Exchanges code + PKCE verifier for tokens
    """
    with tracer.start_as_current_span("oauth.interactive_login") as span:
        _CallbackState.completion_event.clear()
        _CallbackState.received_code = None
        _CallbackState.auth_error = None
        state = secrets.token_urlsafe(16)
        _CallbackState.expected_state = state

        code_verifier, code_challenge = generate_pkce_pair()

        try:
            httpd = HTTPServer((CALLBACK_HOST, CALLBACK_PORT), OAuthCallbackHandler)
        except OSError as e:
            span.record_exception(e)
            raise RuntimeError(f"Failed to start callback listener on {CALLBACK_HOST}:{CALLBACK_PORT}: {e}")

        server_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        server_thread.start()

        auth_params = {
            "response_type": "code",
            "client_id": CLIENT_ID,
            "redirect_uri": REDIRECT_URI,
            "scope": AUTH_SCOPE,
            "state": state,
            "code_challenge": code_challenge,
            "code_challenge_method": "S256",
        }
        auth_url = f"{KEYCLOAK_ISSUER_URL.rstrip('/')}/protocol/openid-connect/auth?{urllib.parse.urlencode(auth_params)}"

        sys.stderr.write("Initiating Polaris OAuth 2.0 Authorization Code flow with PKCE...\n")
        sys.stderr.write(f"Listening on {REDIRECT_URI}\n")
        sys.stderr.write(f"Authorization URL: {auth_url}\n")
        sys.stderr.write("Opening browser to authenticate (timeout: 120s)...\n")

        webbrowser.open(auth_url)

        completed = _CallbackState.completion_event.wait(timeout=timeout)
        httpd.shutdown()
        httpd.server_close()

        if not completed:
            err = TimeoutError("OAuth 2.0 authorization timed out waiting for callback.")
            span.record_exception(err)
            raise err
        if _CallbackState.auth_error:
            err = RuntimeError(f"OAuth 2.0 authorization error: {_CallbackState.auth_error}")
            span.record_exception(err)
            raise err
        if not _CallbackState.received_code:
            err = RuntimeError("OAuth 2.0 callback did not provide an authorization code.")
            span.record_exception(err)
            raise err

        tokens = exchange_code_for_tokens(_CallbackState.received_code, code_verifier)
        sys.stderr.write("Authentication successful! Token cached.\n")
        return tokens


def programmatic_code_flow(username: str, password: str) -> Dict[str, Any]:
    """
    Executes the full Authorization Code Flow with PKCE programmatically without a browser.
    Used for automated test suites to verify RFC 7636 conformance end-to-end.
    """
    with tracer.start_as_current_span("oauth.programmatic_code_flow") as span:
        code_verifier, code_challenge = generate_pkce_pair()
        state = secrets.token_urlsafe(16)

        auth_params = {
            "response_type": "code",
            "client_id": CLIENT_ID,
            "redirect_uri": REDIRECT_URI,
            "scope": AUTH_SCOPE,
            "state": state,
            "code_challenge": code_challenge,
            "code_challenge_method": "S256",
        }
        auth_url = f"{KEYCLOAK_ISSUER_URL.rstrip('/')}/protocol/openid-connect/auth?{urllib.parse.urlencode(auth_params)}"

        cookie_processor = urllib.request.HTTPCookieProcessor()

        class StopRedirectHandler(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                if newurl.startswith(REDIRECT_URI):
                    return None
                return super().redirect_request(req, fp, code, msg, headers, newurl)

        opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=SSL_CONTEXT),
            cookie_processor,
            StopRedirectHandler(),
        )

        # 1. Fetch Keycloak login form
        req = urllib.request.Request(auth_url, headers={"User-Agent": "Polaris-MCP-Client/1.0"})
        with opener.open(req) as resp:
            html = resp.read().decode("utf-8")

        action_match = re.search(r'action="([^"]+)"', html)
        if not action_match:
            raise RuntimeError("Failed to parse login action URL from Keycloak authentication page.")
        action_url = action_match.group(1).replace("&amp;", "&")

        # 2. Submit credentials
        login_payload = urllib.parse.urlencode({
            "username": username,
            "password": password,
            "credentialId": "",
        }).encode("utf-8")

        post_req = urllib.request.Request(
            action_url,
            data=login_payload,
            headers={
                "Content-Type": "application/x-www-form-urlencoded",
                "User-Agent": "Polaris-MCP-Client/1.0",
            },
        )

        try:
            resp = opener.open(post_req)
            redirect_url = resp.geturl()
        except urllib.error.HTTPError as e:
            if e.code in (302, 303, 307):
                redirect_url = e.headers.get("Location")
            else:
                raise RuntimeError(f"Keycloak form submission failed with HTTP {e.code}: {e.read().decode('utf-8')}")

        if not redirect_url or not redirect_url.startswith(REDIRECT_URI):
            raise RuntimeError(f"Expected redirect to {REDIRECT_URI}, but got: {redirect_url}")

        parsed_callback = urllib.parse.urlparse(redirect_url)
        callback_params = urllib.parse.parse_qs(parsed_callback.query)

        if "error" in callback_params:
            raise RuntimeError(f"Keycloak login error: {callback_params.get('error_description', callback_params['error'])[0]}")

        received_code = callback_params.get("code", [None])[0]
        if not received_code:
            raise RuntimeError(f"No code parameter found in callback URL: {redirect_url}")

        return exchange_code_for_tokens(received_code, code_verifier)
