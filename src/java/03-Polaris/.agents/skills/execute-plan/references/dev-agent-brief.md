# Dev-Agent Brief (template)

Fill in the `<…>` fields and send the whole block as the `prompt` of the dev-agent's `Agent` call. Paste the slice section from the plan **verbatim**. Don't summarize it.

---

You are the dev-agent for slice **<slice-id>: <slice title>** of the plan `<repo path to plan>`. Your role, principles and quality bar are in `src/java/03-Polaris/.agents/agents/domain-dev-agent.md`. Read it first, then `src/java/03-Polaris/AGENTS.md`, then the plan. You own only this slice.

## Slice (from the plan)

<paste the full slice section: files, numbered steps, tests>

Plan context you must honour: <shared-file or migration-number rules, contracts/§ references, relevant decisions (e.g. "D4: repeated claim → 409")>.
Already merged into `main`: <slice ids, or "none">.

## Setup

You are in an isolated git worktree of the monorepo. The project lives in `src/java/03-Polaris/`.

```bash
git fetch origin
git switch -c slice/<plan-slug>/<slice-id> origin/main
```

## Do

1. Check the slice against the code. If the plan is wrong about the code (a file is missing, a line reference is stale, a contract conflicts), or the slice can't be done as one vertical change, **stop and report `BLOCKED`** with the specifics and a proposed fix. Don't improvise the scope.
2. Implement the slice, test first. Use the `testable-code` and `polaris-dev` skills. Stay inside the slice's bounded context and files. Anything outside them goes under follow-ups in your report, not into the code.
3. Verify: this PR merges straight into `main`, so run `mvn clean test` for the **whole reactor** from `src/java/03-Polaris/`, not just your modules. The slice must also be safe to ship on its own: no existing behaviour broken, nothing half-wired exposed to users. Testcontainers is fine in parallel. Only run `make up` (the shared compose stack) if the slice needs it, and say so in your report.
4. Commit with Conventional Commits (`feat(<context>): …`, `test(…)`, `docs(…)`), ending each message with the attribution lines from your system instructions.
5. Publish and open the PR **into `main`** (trunk-based: your branch lives only until this PR merges):

```bash
git push -u origin slice/<plan-slug>/<slice-id>
gh pr create --base main --head slice/<plan-slug>/<slice-id> \
  --title "<slice-id>: <slice title>" --body-file <body>
```

PR body:

```markdown
🤖 [execute-plan] Slice <slice-id> of [<plan title>](<plan path>) · part of #<tracking issue>

## What
<2–4 bullets, mapped to the slice's numbered steps>

## Tests
<test classes and what they prove; the command you ran and its result>

## Deviations from the plan
<none, or each deviation and why>

## Follow-ups
<out-of-slice items you noticed, or none>
```

## Report, then stop

End your turn with exactly one of these:

- `PR_OPENED #<n> <url>`, followed by the test command and its result, any deviations, and follow-ups.
- `BLOCKED`, followed by the problem, the evidence (file:line), and the fix you propose.

Then **stop and wait**. Don't merge, don't approve, and don't start other slices. The coordinator will message you later with one of:

- **Review feedback:** fix every point on the same branch, push new commits (no force-push unless asked to rebase), reply `FIXED #<n>` with one line per point. If you disagree with a point, say why instead of changing the code.
- **Rebase request:** `git fetch origin && git rebase origin/main`, resolve conflicts, re-run the tests, `git push --force-with-lease`, reply `REBASED #<n>`.
- **Merged:** you're done. Reply `DONE`.
