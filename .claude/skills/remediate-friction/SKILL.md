---
name: remediate-friction
description: Drains everything under the SDLC epic (computenet-wpvy) on its own lane, separate from /work — picks the most-reported open item, re-validates it against the current skill revision, and fixes .claude/skills/ in a reviewed branch and PR. Use when a routine kicks off friction remediation, or the user says "/remediate-friction", "drain the friction log", "fix the work skill friction", or wants the accumulated SDLC backlog worked.
---

# /remediate-friction

Drains **everything under `computenet-wpvy`, the SDLC epic** — by fixing `.claude/skills/`
(`work/` above all — SKILL.md, `references/`, `scripts/` — and this lane's own
files). Run on demand, or reactively via the
trigger below.

## The reactive trigger

`scripts/gate.sh` + `scripts/install-trigger.sh` make this lane
event-driven: `bd` rewrites `.beads/issues.jsonl` on every mutation, so a
launchd `WatchPaths` agent fires the gate the moment anything is filed —
including the step-7 filings of a /work session finishing on this machine.
The gate exits in milliseconds when nothing is actionable, holds a
single-flight lock (with stale-lock recovery), and otherwise launches one
headless `claude -p "/remediate-friction"` under a watchdog cap
(`SDLC_MAX_SECONDS`, default 90m). The plist adds an hourly `StartInterval`
as the cross-machine fallback — items filed and *not* claimed by the other
machine become visible here only after a Dolt sync, so the watcher covers
the local case and the hourly tick covers the rest.

Install once per machine, from a shell where `BEADS_ACTOR`, `claude`, `bd`
and `jq` resolve (their paths and the actor get baked into the plist):

```bash
.claude/skills/remediate-friction/scripts/install-trigger.sh
```

Nothing installs itself — a human runs that. First firing may need a macOS
Files-and-Folders approval; the log is
`~/Library/Logs/sdlc-orchestrator.log`, and `SDLC_DRY_RUN=1 scripts/gate.sh`
shows what the filter would do without launching anything.

This lane is deliberately separate from /work: a work session must never
edit the skill it is executing under, and this orchestrator must never look
like a work session to /work's concurrent-run check.

**The two lines that keep the lanes apart:**

- **Never claim the SDLC epic.** Address it by id and claim only its child
  items. Because this lane never puts an epic `in_progress`, /work's
  concurrent-run check (which lists `in_progress` epics) never sees it, and
  no work-session epic claim is consumed.
- **Touch only `.claude/skills/work/` and `.claude/skills/remediate-friction/`.**
  Product code is /work's lane.

## 1. Identity and sync

```bash
echo "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"
git fetch origin main
bd dolt pull
```

Same sync principle as /work (`references/claim-sync.md` there): this pull
plus a publication push at the end, with an extra push whenever step 2
acquires an item this machine doesn't already hold. If the pull fails, stop
and report.

## 2. Pick the most-reported item

```bash
bd list --parent=computenet-wpvy --status=open --json
```

**Scope is parentage, not the `skill-friction` label.** Anything under this
epic is SDLC work and belongs to this lane. The label is provenance — step 7
of `work` applies it to what a session files at runtime — and it is *not* a
gate. There is no second lane for an unlabelled child to belong to: `/work`
is excluded from this epic entirely, on every route (`work`'s "The SDLC
exclusion").

Filtering on the label is how three unlabelled children — `wpvy.25`, `.26`,
`.27` — sat open on 2026-08-14 while this lane reported the log drained.

`--parent` is not transitive, so this listing shows direct children only. If
the epic ever grows a feature layer, walk descendants with `epic_of` from
`work`'s 5b rather than assuming one level. Order by
`comment_count` descending — comment count is recurrence, and recurrence is
priority. Skip anything labeled `human`. Then pick by assignee — filing
machines pre-claim items (SKILL.md step 7), and the claim decides which
orchestrator drains what:

- **Assigned to `$BEADS_ACTOR`** → yours already; work it, no sync needed.
- **Unassigned** → an acquisition: claim by id, `bd dolt push`. Push
  rejected → pull, re-check the assignee, take the next item if it's gone.
- **Assigned to the other machine** → theirs; skip it — *unless* the claim
  is stale (untouched for more than 12h, per `updated_at` from the step-1
  pull). Then steal it with the full bracket: `bd dolt pull` to confirm it
  is still untouched, re-claim, `bd dolt push`, and note the steal in a
  comment. This is the liveness backstop for a machine that claimed at
  filing and died before draining.

```bash
bd update <id> --claim
```

Nothing workable → report the log is drained (or fully claimed elsewhere)
and stop.

## 3. Re-validate against the current skill revision

```bash
git hash-object .claude/skills/work/SKILL.md      # current revision
bd show <id> --json                               # .[0].metadata.skill_version
bd comments <id> --json                           # the instances
```

The item's `skill_version` is the revision the reporting session ran under
(items predating versioning have none — treat as superseded). If it differs
from the current hash, the skill has changed since the report: re-read the
current text against the complaint **before** fixing anything.

- Already fixed by an intervening revision → `bd close <id> --reason
  "superseded: fixed as of <current-hash>"`. Go to step 2 for the next item.
- Still real → `bd update <id> --set-metadata skill_version=<current-hash>`
  (re-validated against current) and continue.

## 4. Fix, review, ship

One item per branch, from `origin/main`:

```bash
.claude/skills/work/scripts/ensure-worktree.sh \
  "$PWD/../computenet-worktrees/<id>" friction/<id> origin/main
```

**Before you commit, run the skill validator.** The lane edits skills
constantly, which is how they drift from what a skill is expected to be
(computenet-wpvy.38):

```bash
ruby .claude/skills/remediate-friction/scripts/validate-skills.rb
```

It checks every skill under `.claude/skills/` against Anthropic's
skill-creator structural criteria — frontmatter parses, keys are known, name
is kebab-case and <=64 chars, description <=1024 chars with no angle brackets
— and exits non-zero on any failure. Expect `4 skill(s) checked, 0 failing`.
It deliberately does **not** run skill-creator's behavioural eval
(`run_eval.py` and the grader agents): that spawns with-skill and baseline
runs over authored test cases and takes hours, so it belongs on a cadence or
on demand, never as a per-change gate.

Make the smallest change to the skill files that stops the friction
recurring — the item's `--acceptance` says what that is. Quote the item id
and the target skill revision hash in the commit message and PR body, so
the fix is attributable to the revision it amends.

Commit, push, open a **draft** PR. Then dispatch a fresh reviewer agent
(never yourself in the same breath) to check the fix against the item's
acceptance and the surrounding skill text; ship per AGENTS.md's confidence
rule — the reviewer certifies, you run `gh pr ready`.

On merge:

```bash
bd close <id> --reason "fixed in <pr-url> against skill revision <hash>"
git worktree remove "$PWD/../computenet-worktrees/<id>"
```

Repeat from step 2 while budget remains; this lane is cheap per item, so
several items per session is normal.

## 5. Finalize

```bash
bd dolt push
```

Report: items closed (with PRs), items superseded-closed, items left open
and why, and the one-command oversight view for the human:

```bash
bd list --parent=computenet-wpvy --status=open --json
```
