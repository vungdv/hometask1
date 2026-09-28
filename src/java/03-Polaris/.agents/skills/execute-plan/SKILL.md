---
name: execute-plan
description: Executes a development plan from docs/development/plan/ end to end, trunk-based. Requires a plan file path as argument, e.g. `/execute-plan docs/development/plan/<plan>.md`.
argument-hint: <path-to-plan-file>
---

# Execute a Development Plan (trunk-based)

## Input: plan file (required)

This skill requires exactly one plan file, passed as the argument: `$ARGUMENTS`

Before doing anything else, validate the input:

1. **Missing argument** — if no path was given, STOP. Do not guess or pick a plan yourself. List the files in
   `docs/development/plan/` and ask the user which one to execute.
2. **File not found** — if the path does not exist (also try resolving it relative to `docs/development/plan/`),
   STOP and report the path you tried, plus the available plans in `docs/development/plan/`.
3. **Not a plan** — if the file is not a Markdown file under `docs/development/plan/`, or it contains no
   identifiable slices, STOP and tell the user why it was rejected.

Only once the plan file is validated, read it in full. It is the **[plan]** referenced below and the single
source of truth for slices, ordering, and acceptance criteria. Never execute work that is not in the [plan].

## Roles

Execution follows a **hub-spoke** model: you are the hub, and all communication goes through you. The worker
and the reviewer never talk to each other directly.

| Role | Who | Responsible for | Does NOT |
|------|-----|-----------------|----------|
| **Coordinator** | You | Picking the next slice from the [plan], handing it off, tracking status, and deciding when a slice is done | Write production code or review PRs yourself |
| **Worker** | Sub-agent | Implementing exactly one slice on its own short-lived branch, keeping it vertically complete and tested, then opening a PR | Change scope beyond the slice, or merge its own PR |
| **Reviewer** | Sub-agent | Reviewing the worker's PR against the slice's acceptance criteria and `AGENTS.md`, and posting feedback as comments on the PR | Push fixes to the branch |

### Slice lifecycle

1. **Hand-off:** the coordinator picks the next slice from the [plan] and hands it to a worker.
2. **Implement:** the worker creates a slice branch from trunk, implements the slice, opens a PR, and reports
   back with a status (`PR-created` plus the PR link, or `blocked` plus the reason).
3. **Review:** the coordinator assigns the PR to a reviewer.
4. **Feedback:** the reviewer comments directly on the PR, then reports back with a verdict (`approved` or
   `changes-requested`).
5. **Iterate:** if changes are requested, the coordinator sends the worker back to address the PR comments,
   then repeats steps 3–4.
6. **Close:** once the PR is approved and merged to trunk, the coordinator marks the slice done and picks the next one.

Every hand-off, to the worker or the reviewer, must include the plan file path and the slice identifier. That
way each agent reads the slice from the [plan] itself, not from the coordinator's summary of it.
