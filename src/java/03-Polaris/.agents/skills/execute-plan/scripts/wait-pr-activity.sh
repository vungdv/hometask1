#!/usr/bin/env bash
# Block until a pull request needs the coordinator's attention, then print what changed.
#
# Usage: wait-pr-activity.sh <pr-number> [--since <ISO-8601 UTC>] [--interval <sec>] [--timeout <sec>]
#
# Returns when the PR is no longer OPEN (merged/closed) or a human posts a new
# issue comment, review, or inline review comment after --since (default: now).
# Comments starting with the coordinator marker are ignored: every gh call runs
# as the same GitHub account, so the marker is the only way to tell them apart.
#
# Output (stdout):
#   STATE: OPEN|MERGED|CLOSED
#   SIGNAL: MERGE              (a new human comment is exactly "/merge")
#   NEW: <kind> <author> <url> [<path>:<line>]
#   <body, indented>
#
# Exit codes: 0 activity found · 2 timed out with no activity · 1 usage error or GitHub unreachable

set -euo pipefail

MARKER='🤖 [execute-plan]'
# GitHub's REST rate limit is 5000 req/h; 3 calls per minute per watcher leaves room for many watchers.
INTERVAL=60
# A human review round rarely exceeds a working afternoon; the coordinator restarts the watcher on timeout.
TIMEOUT=14400
# Tolerate a few minutes of network or API blips before giving up.
MAX_CONSECUTIVE_FAILURES=5

usage() { sed -n '2,17p' "$0" >&2; exit 1; }

[[ $# -ge 1 && $1 =~ ^[0-9]+$ ]] || usage
PR=$1; shift
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
while [[ $# -gt 0 ]]; do
  case $1 in
    --since) SINCE=$2; shift 2 ;;
    --interval) INTERVAL=$2; shift 2 ;;
    --timeout) TIMEOUT=$2; shift 2 ;;
    *) usage ;;
  esac
done

command -v gh >/dev/null || { echo "gh CLI not found; install it and run 'gh auth login'" >&2; exit 1; }

# One JSON object per human item created after $SINCE: {kind, at, author, url, where, body}.
human_items() {
  local filter='select((.body // "") | startswith($m) | not)'
  {
    gh api --paginate "repos/{owner}/{repo}/issues/$PR/comments" \
      --jq ".[] | {kind:\"comment\", at:.created_at, author:.user.login, url:.html_url, where:\"\", body:.body}"
    gh api --paginate "repos/{owner}/{repo}/pulls/$PR/comments" \
      --jq ".[] | {kind:\"inline\", at:.created_at, author:.user.login, url:.html_url, where:\"\(.path):\(.line // .original_line)\", body:.body}"
    # Reviews without a body only wrap inline comments, which are already listed above.
    gh api --paginate "repos/{owner}/{repo}/pulls/$PR/reviews" \
      --jq ".[] | select((.body // \"\") != \"\") | {kind:\"review:\(.state)\", at:.submitted_at, author:.user.login, url:.html_url, where:\"\", body:.body}"
  } | jq -c --arg m "$MARKER" --arg since "$SINCE" "$filter | select(.at > \$since)"
}

deadline=$(( $(date +%s) + TIMEOUT ))
failures=0

while :; do
  if state=$(gh pr view "$PR" --json state --jq .state 2>/dev/null) && items=$(human_items 2>/dev/null); then
    failures=0
    if [[ $state != OPEN || -n $items ]]; then
      echo "STATE: $state"
      if [[ -n $items ]] && jq -e -s 'any(.[]; (.body | gsub("^\\s+|\\s+$"; "")) == "/merge")' <<<"$items" >/dev/null; then
        echo "SIGNAL: MERGE"
      fi
      [[ -n $items ]] && jq -r '"NEW: \(.kind) \(.author) \(.url) \(.where)\n\(.body | split("\n") | map("    " + .) | join("\n"))"' <<<"$items"
      exit 0
    fi
  else
    failures=$(( failures + 1 ))
    echo "warn: GitHub query failed ($failures/$MAX_CONSECUTIVE_FAILURES)" >&2
    (( failures >= MAX_CONSECUTIVE_FAILURES )) && { echo "error: GitHub unreachable for PR #$PR" >&2; exit 1; }
  fi
  if (( $(date +%s) >= deadline )); then
    echo "TIMEOUT: no activity on PR #$PR since $SINCE"
    exit 2
  fi
  sleep "$INTERVAL"
done
