# Review Gate: one slice PR

Two tiers. The AI review filters out mechanical problems so the human's time goes to judgement. **Only the human can release a merge.**

## Contents
- Tier 1: AI review
- Tier 2: human gate
- Merging
- Relaying feedback

## Tier 1: AI review

When a dev-agent reports `PR_OPENED #<n>`, set the slice's tracker status to `in review` and spawn a fresh reviewer. A reviewer with no ties to the author catches more.

`Agent(subagent_type: "general-purpose", name: "review-<slice-id>", prompt: …)`, where the prompt is:

```
Review PR #<n> (`gh pr diff <n>`, `gh pr view <n>`) for slice <slice-id> of <plan path>.
It merges straight into main (trunk-based). Read the slice section of the plan and AGENTS.md.
Check, and cite file:line for each finding:
1. Plan conformance: every numbered step and listed test of the slice is done; nothing out of slice scope.
2. AGENTS.md Principle 2 self-check: works end-to-end, one bounded context, cross-context only via published contracts.
3. Correctness: bugs, races, transaction/after-commit boundaries, error paths (RFC 7807), security.
4. Tests: the slice's scenarios are covered and would fail without the change; no mocks of internal seams.
5. Observability: the spans, metrics and log fields the slice names.
Classify each finding as BLOCKER (must fix) or NIT (optional). Don't report style preferences.
Post the result as one PR comment starting with "🤖 [execute-plan] AI review round <r>" (gh pr comment <n> --body-file …),
then reply with: VERDICT: PASS | CHANGES, followed by the BLOCKER list.
```

- `CHANGES`: send the BLOCKERs to the dev-agent (see "Relaying feedback") and wait for `FIXED`, then run the reviewer again as round r+1. You may send a follow-up to the same reviewer with `SendMessage` so it re-checks only the new commits.
- `PASS`: go to Tier 2.
- After 3 rounds, go to Tier 2 anyway, and put the open BLOCKERs at the top of your handoff message.

## Tier 2: human gate

Set the slice's status to `awaiting human`. Tell the human in chat, in one short block (also send a `PushNotification` if that tool is available):

```
Slice <slice-id> ready for your review: <PR url>
AI review: PASS after <r> round(s) | <k> open blockers: …
To approve: merge the PR on GitHub, or comment `/merge` on it (or tell me here).
Anything else you comment is sent to the dev-agent as feedback.
```

Then watch the PR in the background, so you're re-invoked when it changes instead of polling:

```bash
Bash(run_in_background: true):
  src/java/03-Polaris/.agents/skills/execute-plan/scripts/wait-pr-activity.sh <n>   # from the repo root
```

The script exits with the PR's new state and any new human comments. It ignores comments that start with `🤖 [execute-plan]`. Watch several PRs by starting one watcher per PR. While you wait, keep working on other slices.

The human can't use GitHub's "Approve", because they authored these PRs through `gh`. So approval is one of these:

| Signal | Action |
|:--|:--|
| PR `MERGED` (the human merged it) | Go to "After merge" |
| A human comment that is exactly `/merge`, or the human says "merge <slice>" in chat | Merge it yourself (below) |
| Any other human comment or inline review comment | Relay it as feedback, run Tier 1 again on the new commits (1 round), then return to Tier 2 |
| PR `CLOSED` without merge | Ask the human whether to drop, re-plan, or restart the slice |
| Watcher `TIMEOUT` | Restart it. Don't nag the human |

## Merging

Only after an approval signal:

```bash
gh pr merge <n> --squash --delete-branch
```

If it's not mergeable (conflicts), send the dev-agent a rebase request, wait for `REBASED`, then merge. A rebase with non-trivial conflict resolution counts as a new change, so run Tier 1 once more first.

### After merge
1. Send the dev-agent `Merged.` and set the slice's status to `merged` in the tracking issue.
2. Run `git fetch origin`. Recompute which slices are ready, and dispatch them (SKILL.md Step 2).
3. If the merged diff touched files that an open sibling PR also touches (`gh pr diff <m> --name-only`), send that sibling's dev-agent a rebase request.

## Relaying feedback

When you forward feedback to the dev-agent, send one numbered list. Quote each point with its source (reviewer or human, plus file:line), and keep the human's wording word for word. Don't soften it, reinterpret it, or add your own requests. If a human comment is a question for you rather than feedback on the code, answer it on the PR yourself, with the marker.
