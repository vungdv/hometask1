#!/usr/bin/env python3
"""Apply folder-permissions.json to Grafana through its HTTP API (plan O7, OBS-SEC-1). Idempotent, stdlib only.

Env: GRAFANA_URL (http://grafana:3000), GRAFANA_USER, GRAFANA_PASSWORD, PERMISSIONS_FILE, WAIT_SECS (90).
Exit 0 only when every listed folder exists and its permissions read back exactly as declared.
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

URL = os.environ.get("GRAFANA_URL", "http://grafana:3000").rstrip("/")
AUTH = "Basic " + base64.b64encode(f"{os.environ['GRAFANA_USER']}:{os.environ['GRAFANA_PASSWORD']}".encode()).decode()
LEVEL = {"View": 1, "Edit": 2, "Admin": 4}
WAIT = int(os.environ.get("WAIT_SECS", "90"))


def call(method, path, body=None):
    req = urllib.request.Request(URL + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": AUTH, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read() or b"null")


def main():
    declared = json.load(open(os.environ.get("PERMISSIONS_FILE", "/access/folder-permissions.json")))["folders"]
    deadline = time.time() + WAIT
    folders = {}
    while True:  # dashboard provisioning creates the folders shortly after Grafana starts
        try:
            folders = {f["title"]: f["uid"] for f in call("GET", "/api/folders")}
            if all(t in folders for t in declared):
                break
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        if time.time() > deadline:
            sys.exit(f"FAIL folders not found in {WAIT}s: {sorted(set(declared) - set(folders))}")
        time.sleep(3)
    rc = 0
    for title, roles in declared.items():
        uid = folders[title]
        items = [{"role": r, "permission": LEVEL[p]} for r, p in roles.items()]
        call("POST", f"/api/folders/{uid}/permissions", {"items": items})
        got = {(p["role"], p["permission"]) for p in call("GET", f"/api/folders/{uid}/permissions") if p.get("role")}
        want = {(r, LEVEL[p]) for r, p in roles.items()}
        # Admin is implicit (Grafana adds it); anything else must match exactly.
        if got - {("Admin", 4)} != want:
            print(f"FAIL {title}: permissions {sorted(got)} != declared {sorted(want)}")
            rc = 1
        else:
            print(f"OK   folder {title}: {roles}")
    sys.exit(rc)


main()
