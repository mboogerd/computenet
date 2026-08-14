---
name: remediate-friction
description: Drains the skill-friction-labeled backlog — every open item carrying that label, wherever in the tracker it sits — on its own lane, separate from /work; picks the most-reported open friction bug or feature, re-validates it against the current skill revision, and fixes .claude/skills/work/ in a reviewed branch and PR. Use when a routine kicks off friction remediation, or the user says "/remediate-friction", "drain the friction log", "fix the work skill friction", or wants the accumulated skill-friction backlog worked.
---

# /remediate-friction

Drains the open `skill-friction`-labeled bugs and features by fixing
`.claude/skills/work/` (SKILL.md, `references/`, `scripts/`). Run on demand,
or reactively via the trigger below.

**The label defines this lane, not a parent epic.** New friction is *filed*
under `computenet-wpvy`, the SDLC epic (that is /work's SKILL.md step 7, and
it is unchanged), but items are *selected* here by `skill-friction` alone.
The two are deliberately different: the label predates the SDLC epic, so much
of the log hangs off the older WSK epics or off nothing at all, and a
parent-scoped query would leave most of the backlog undrainable.

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

- **Never claim an epic.** Claim individual items only — not the SDLC epic,
  and not whichever epic a friction item happens to hang off. Because this
  lane never puts an epic `in_progress`, /work's concurrent-run check (which
  lists `in_progress` epics) never sees it, and no work-session epic claim is
  consumed.
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
bd list --label=skill-friction --status=open --exclude-type=epic --json --limit 0
```

No `--parent`: the label is the whole selector, and scoping to
`computenet-wpvy` would hide the items filed before that epic existed. The
label is also what keeps the lane clean in the other direction — the epics
these items sit under hold plenty of ordinary work that belongs to /work
sessions, not here. The other two flags earn their place once `--parent` is
gone: `--exclude-type=epic` because two of the WSK epics carry the
`skill-friction` label themselves, and claiming one would break the
never-claim-an-epic rule above; `--limit 0` because `bd list` defaults to 50
and the open log already sits close to that, and a silently truncated list
would corrupt the ordering below.

Order by `comment_count` descending — comment count is recurrence, and
recurrence is priority. Skip anything labeled `human`. Then pick by assignee
— filing machines pre-claim items (SKILL.md step 7), and the claim decides
which orchestrator drains what:

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
bd list --label=skill-friction --status=open --exclude-type=epic --json --limit 0
```
