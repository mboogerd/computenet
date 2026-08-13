---
name: remediate-friction
description: Drains the standing skill-friction epic (computenet-mfaw) on its own lane, separate from /work — picks the most-reported open friction item, re-validates it against the current skill revision, and fixes .claude/skills/work/ in a reviewed branch and PR. Use when a routine kicks off friction remediation, or the user says "/remediate-friction", "drain the friction log", "fix the work skill friction", or wants the accumulated skill-friction backlog worked.
---

# /remediate-friction

Drains `computenet-mfaw`, the standing skill-friction epic, by fixing
`.claude/skills/work/` (SKILL.md, `references/`, `scripts/`). This lane is
deliberately separate from /work: a work session must never edit the skill
it is executing under, and this orchestrator must never look like a work
session to /work's concurrent-run check.

**The two lines that keep the lanes apart:**

- **Never claim, undefer, or close `computenet-mfaw` itself.** It is
  `defer`-ed on purpose (hidden from `bd ready --type=epic`) and is a
  container, not a unit of work. Address it by id only. Because it never
  goes `in_progress`, /work's concurrent-run check (which lists
  `in_progress` epics) never sees this lane, and no work-session epic claim
  is consumed.
- **Touch only `.claude/skills/work/` and `.claude/skills/remediate-friction/`.**
  Product code is /work's lane.

## 1. Identity and sync

```bash
echo "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"
git fetch origin main
bd dolt pull
```

One pull here, one push at the end — same discipline as /work. If the pull
fails, stop and report.

## 2. Pick the most-reported item

```bash
bd list --parent=computenet-mfaw --status=open --json
```

Order by `comment_count` descending — comment count is recurrence, and
recurrence is priority. Skip anything labeled `human` or already
`in_progress` (another remediation run owns it). Claim the winner **by
id**:

```bash
bd update <id> --claim
```

Nothing open → report the log is drained and stop.

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
bd list --parent=computenet-mfaw --status=open --json
```
