---
name: execute-plan
description: Executes a development plan from docs/development/plan/ end to end, trunk-based. Opens a tracking issue, runs dev-agents that each implement one slice on a short-lived branch and open a PR straight into main (independent slices run in parallel), coordinates AI review with a human as the final reviewer, then verifies the Definition of Done on main. Use when the user asks to execute, implement, deliver, or "run" a plan, or to continue one already in progress.
---

# Execute a Development Plan (trunk-based)

You are the **coordinator**. You don't write application code. Dev-agents implement slices, a reviewer agent checks them, and the human has the final say on every merge. There is no feature branch: each slice merges into `main` on its own.

**Standing rules**
- Never push to `main` directly. Every change reaches `main` through a slice PR, and none merges until the human approves it (see [references/review-gate.md](references/review-gate.md)).
- **Every merge must leave `main` green and shippable.** A slice may add unused capability (e.g. publish an event with no consumer yet). It must not break existing behaviour or expose half-wired features.
- Start every comment you post on GitHub with the marker `🤖 [execute-plan]`. All `gh` calls run as the human's account, and `scripts/wait-pr-activity.sh` uses the marker to tell your comments apart from theirs.
- The plan is the spec. If a slice turns out wrong or too large, stop and ask the human. Don't silently change the scope.

Copy this checklist into your response and keep it updated:

```
Plan execution: <plan file>
- [ ] Step 0: Read the plan, build the slice graph, confirm with the human
- [ ] Step 1: Open the tracking issue
- [ ] Step 2: Dispatch dev-agents for every ready slice
- [ ] Step 3: Review each slice PR (AI review, then human gate), merge to main, dispatch newly ready slices
- [ ] Step 4: Finalize: verify the Definition of Done on main, close the tracking issue
```

## Step 0: Read the plan

1. Read the whole plan file, then `AGENTS.md`. Skim each document the plan links to as its requirement and design baseline.
2. Extract the **slice table**. For each slice, record its ID, title, bounded context, dependencies, and the files and tests it names. Sources for dependencies:
   - the plan's dependency diagram (e.g. `F0 ──► F1 ──► F2`);
   - ordering rules in prose, including constraints on **other plans** (e.g. "land F1 **after** S4"). A slice whose external prerequisite isn't on `main` yet is `blocked`, not `ready`;
   - shared migration numbers or shared files between siblings. Treat these as a soft dependency: run the siblings in parallel, but expect a rebase.
3. Check each slice is **safe to ship alone**: if merging it before its dependents would break or half-expose behaviour on `main`, flag it. Propose a fix (reorder, or ship behind a config flag that defaults off) and let the human choose.
4. Check the plan's **Decisions Needed** and **Status**. Unresolved decisions go to the human now, using the plan's recommendation as the default option.
5. Show the human the slice table, the parallel waves (e.g. wave 1: F0; wave 2: F1, F4, F5), and any open decisions or ship-alone risks. **Wait for confirmation before Step 1.**

If a tracking issue for this plan already exists (`gh issue list --state open --search "<plan-slug> in:title"`), you are resuming. Rebuild state from its body and from `gh pr list --state all --search "head:slice/<plan-slug>/"`, then continue at the right step.

## Step 1: Open the tracking issue

`<plan-slug>` is the plan's file name without `.md`. The tracking issue is the durable state of the run. It survives context resets and shows the human the progress:

```bash
gh issue create --title "Plan: <plan title> (<plan-slug>)" --body-file <tracking body>
```

```markdown
🤖 [execute-plan] Tracking the trunk-based execution of [<plan title>](<repo path to plan>). Each slice merges into `main` through its own PR after human review.

| Slice | Context | Depends on | Branch | PR | Status |
|:--|:--|:--|:--|:--|:--|
| F0 | Platform | — | `slice/<plan-slug>/f0` | #123 | merged |
| F1 | Order | F0, S4 (other plan) | — | — | blocked |
```

Status values: `blocked` · `ready` · `in progress` · `in review` · `awaiting human` · `merged`. Update the table (`gh issue edit <n> --body-file …`) every time a status changes.

## Step 2: Dispatch dev-agents

A slice is **ready** when every dependency is `merged` (on `main`) and it has no external blocker. For each ready slice:

1. Fill in [references/dev-agent-brief.md](references/dev-agent-brief.md) for that slice.
2. Spawn it: `Agent(subagent_type: "general-purpose", isolation: "worktree", name: "dev-<slice-id>", prompt: <brief>)`. Always use a worktree. Parallel agents sharing one checkout overwrite each other's files.
3. Spawn every ready slice **in the same message** so they run in parallel. Keep slices that are not ready waiting.
4. Mark each slice `in progress`, and note which agent owns it.

The agent ends its turn after it opens its PR, or when it's blocked. It stays alive, and you continue it with `SendMessage(to: "dev-<slice-id>")`. Don't spawn a second agent for a slice that already has one. If the agent is gone (a new session), spawn a fresh one with the same brief plus "Resume: PR #<n> exists; read its comments first."

When a dev-agent reports **blocked** (the plan is ambiguous, a slice is too big, or it hit a contract problem), don't guess. Bring the question to the human, then relay the answer with `SendMessage`.

## Step 3: Coordinate review

Follow [references/review-gate.md](references/review-gate.md) for every slice PR. In short:

```
dev-agent opens PR ─► AI review ─► findings? ─yes─► SendMessage fixes ─┐
                          ▲                                            │
                          └────────────────────────────────────────────┘
                     no ─► human gate ─► comments? ─► relay to dev-agent, re-review
                                     └─► approved ─► squash-merge to main ─► dispatch newly ready slices
```

- Cap AI review at **3 rounds** per slice. If findings remain after that, hand the PR to the human with the open findings listed.
- `main` also moves under other sessions and plans. Before merging, the PR must be up to date with `origin/main`. After each merge, tell dev-agents on open PRs that share files with it to rebase.
- After each merge, go back to Step 2 for slices that are now ready.
- While PRs wait on the human, watch them with `scripts/wait-pr-activity.sh` in the background. Don't poll in the foreground.

## Step 4: Finalize

Start only when every slice is `merged`.

1. `git switch main && git pull`, then run the full verification from the plan's **Definition of Done** (e.g. `mvn clean test` from the project root, plus the plan's e2e target). If something fails, fix it with a dev-agent on a `slice/<plan-slug>/fix-<topic>` PR through the same gate.
2. Walk the Definition of Done checklist item by item. Record the evidence (command plus result) or mark the item not met. Never tick an item you didn't verify.
3. Close out the plan document through the same gate: a dev-agent (or you, since this is docs only) opens `slice/<plan-slug>/done`, which sets the plan's `**Status:**` to `Implemented: <tracking issue link>` and ticks the verified DoD boxes.
4. After that PR merges, post a final summary on the tracking issue: the slices with their PRs, the DoD evidence, any deviations from the plan, and follow-ups. Hand it to the human, and close the issue only when they confirm.
5. Clean up. List the leftover agent worktrees (`git worktree list`) and local slice branches, and remove them once the human confirms. Remote slice branches are already deleted at merge time.
