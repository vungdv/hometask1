#!/usr/bin/env bash
# OBS traceability gate (plan O8). Static: needs only python3, no stack. Run it in CI or before a merge.
# Fails when:
#   - an OBS-* requirement ID in docs/operations/high-level-requirement.md (section 4 tables) has no row in
#     docs/operations/verification.md, or a row names an ID that is not a requirement
#   - an `auto` row names a script/file that does not exist (or is not executable when it is a .sh)
#   - a `manual` row names a document or heading anchor that does not exist
# Usage: tests/e2e/observability/traceability-check.sh [requirements.md] [verification.md]
set -uo pipefail
cd "$(dirname "$0")/../../.."
python3 - "${1:-docs/operations/high-level-requirement.md}" "${2:-docs/operations/verification.md}" <<'PY'
import re, sys, os
req_f, map_f = sys.argv[1], sys.argv[2]
fail = 0
def ok(m): print("PASS", m)
def bad(m):
    global fail; fail = 1; print("FAIL", m)
ids = []
for line in open(req_f):
    m = re.match(r"\|\s*(OBS-[A-Z]+-\d+)\s*\|", line)          # a requirement row starts a table line with its ID
    if m and m.group(1) not in ids: ids.append(m.group(1))
if not ids: bad("no OBS-* requirement found in " + req_f); sys.exit(1)
ok(f"{len(ids)} requirements found: {', '.join(ids)}")
rows = {}
def slug(h):
    h = re.sub(r"[`*_]", "", h.strip().lower())
    return re.sub(r"\s", "-", re.sub(r"[^\w\s-]", "", h))
def anchors(path):
    return {slug(m.group(2)) for m in (re.match(r"(#+)\s+(.*)", l) for l in open(path)) if m}
for line in open(map_f):
    m = re.match(r"\|\s*(OBS-[A-Z]+-\d+)\s*\|\s*(auto|manual)\s*\|\s*(\S+)\s*\|", line)
    if not m: continue
    rid, kind, target = m.groups()
    rows.setdefault(rid, []).append((kind, target))
    path, _, anchor = target.partition("#")
    if not os.path.isfile(path):
        bad(f"{rid}: {kind} check '{target}' does not exist"); continue
    if kind == "auto" and path.endswith(".sh") and not os.access(path, os.X_OK):
        bad(f"{rid}: {path} is not executable")
    if kind == "manual":
        if not anchor: bad(f"{rid}: manual step '{target}' needs a #heading anchor")
        elif anchor not in anchors(path): bad(f"{rid}: heading #{anchor} not found in {path}")
for rid in ids:
    r = rows.get(rid, [])
    if not r: bad(f"{rid}: no automated check or recorded manual step in {map_f}")
    elif not any(k == "auto" for k, _ in r) and not any(k == "manual" for k, _ in r): bad(f"{rid}: unmapped")
    else: ok(f"{rid} -> " + ", ".join(f"{k}:{t}" for k, t in r))
for rid in rows:
    if rid not in ids: bad(f"{rid}: mapped but not a requirement in {req_f}")
print(); print("FAIL" if fail else "PASS"); sys.exit(fail)
PY
