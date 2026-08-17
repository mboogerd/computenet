---
name: remediate-friction
description: Owns changes to .claude/skills/ — drains the SDLC epic (computenet-wpvy) on its own lane, separate from /work, and carries the rubric gate every skill edit has to pass. Use when a routine kicks off friction remediation, or the user says "/remediate-friction", "drain the friction log", "fix the work skill friction", or wants the accumulated SDLC backlog worked. Also use for ANY edit to a skill file under .claude/skills/, however it arrives — a one-off fix, a direct request to change the work skill, a correction spotted mid-session — because the rubric check lives here and a skill edited outside this lane silently skips it.
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

**Filing anything new under this epic goes through
`.claude/skills/work/scripts/create-ticket.sh`** — never a hand-typed
`bd create --parent=computenet-wpvy`. The epic is a shared parent, so a
`--parent` create draws its child id from the per-database `child_counters`
and two machines filing between syncs mint the same id for different beads
(`computenet-azt`; `computenet-wpvy.47` is the surviving example). The script
creates unparented for a hash id and re-parents. Dotted ids stay correct for
breakdown children under something this lane has claimed.

`--parent` is not transitive, so this listing shows direct children only. If
the epic ever grows a feature layer, walk descendants with
`.claude/skills/work/scripts/epic-of.sh` rather than assuming one level.
Order by
`comment_count` descending — comment count is recurrence, and recurrence is
priority. Skip anything labeled `human`, and anything labeled
`needs-evidence` — those are parked awaiting a corroborating instance
(step 3), and the un-park is the label's *removal* by the next session that
hits the wall (`work` step 7). Then pick by assignee — filing
machines pre-claim items (SKILL.md step 7), and the claim decides which
orchestrator drains what:

- **Assigned to `$BEADS_ACTOR`** → yours already; work it, no sync needed.
- **Unassigned** → an acquisition: claim by id, `bd dolt push`. Push
  rejected → pull, re-check the assignee, take the next item if it's gone.
- **Assigned to the other machine** → theirs; skip it — *unless* the claim
  is stale (untouched for more than 12h, per `updated_at` from the step-1
  pull; deliberately far above `work`'s 15-minute liveness window, because a
  filing machine holds its claim across whole sessions before draining).
  Then steal it with the full bracket: `bd dolt pull` to confirm it is still
  untouched, re-claim, `bd dolt push`, and note the steal in a comment. This
  is the liveness backstop for a machine that claimed at filing and died
  before draining.

```bash
bd update <id> --claim
```

Nothing workable → report the log is drained (or fully claimed elsewhere)
and stop.

## 3. Triage — a verdict before any fix

A filed item is a claim, not a diagnosis. Of the first 180 items this lane
closed, ~80% were fixed on a single uncorroborated report, and the edits
were not all improvements (computenet-olrv). The fix has to be earned:

```bash
git hash-object .claude/skills/work/SKILL.md      # current revision
bd show <id> --json                               # .[0].metadata.skill_version
bd comments <id> --json                           # the instances
```

`skill_version` is the revision the reporting session ran under (items
predating versioning have none — treat as superseded). If it differs from
the current hash, the skill has changed since the report: re-read the
current text against the complaint **before** judging. Then exactly one
verdict:

- **Superseded** — an intervening revision already fixed it → `bd close
  <id> --reason "superseded: fixed as of <current-hash>"`. Next item.
- **Fix** — only on one of two grounds:
  - *Verified defect*: the claim is checkable right now — the quoted
    instruction really says what the report says it says, and, where the
    claim is about behaviour, the misbehaviour reproduces cheaply (a
    read-only command, a missing path, a flag that errors); a purely
    textual defect — two steps that contradict each other — is verified by
    reading alone. Run the check yourself before believing the report;
    verification substitutes for recurrence.
  - *Recurrent*: at least two independent instances — the filing plus an
    instance comment from a different session. Two is the floor; four is
    the drop-everything signal (`work` step 7).

  Stamp `bd update <id> --set-metadata skill_version=<current-hash>` and go
  to step 4.
- **Needs evidence** — a single instance you cannot cheaply verify, or a
  judgment call ("confusing", "wasteful", "should have X"). Do **not** fix.
  Comment exactly what a future session must capture to make the case — the
  command, the verbatim output, what it cost — then park and release:

  ```bash
  bd update <id> --add-label needs-evidence --assignee="" --status=open
  ```

  All three flags matter: step 2's `--claim` set assignee *and*
  `in_progress`, and an item left `in_progress` is invisible to every
  `--status=open` listing in this file — parked forever, un-parkable by
  anyone.

  The comment is addressed to the next session that hits the same wall, not
  to the human; `work` step 7's upvote branch answers it and removes the
  label, which is what makes the item workable again. Parked items are
  invisible to this lane and to the gate until then.
- **Reject** — the report misreads the skill (quote the text that
  contradicts it), the fix belongs in product code, or the requested change
  would relax an incident-backed rule without engaging that incident's bead
  → `bd close <id> --reason "rejected: <why>"`. Step 7's dedup searches
  closed items too, so a genuine recurrence gets re-filed citing the
  rejection — that is the appeal path, not a burial.

**When the verdict itself is a judgment call** — the evidence bar is met
but whether the change is an improvement is a matter of process taste —
make the call if confident. If not, `bd update <id> --add-label human`,
park the question per `work`'s `references/ask-human.md`, and name which
part you are unsure about. Confidence, not category, decides who calls it.

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
acceptance and the surrounding skill text. The reviewer runs under
`work`'s `references/agent-execution.md` — put that path in the dispatch
prompt, along with the explicit foreground-timeout line every `work`
dispatch carries (a bare Gradle or long command otherwise backgrounds at
120s and the agent stalls). Ship per AGENTS.md's confidence rule — the
reviewer certifies, you run `gh pr ready`.

On merge:

```bash
bd close <id> --reason "fixed in <pr-url> against skill revision <hash>"
git worktree remove "$PWD/../computenet-worktrees/<id>"
```

Repeat from step 2 while budget remains; this lane is cheap per item, so
several items per session is normal.

## 5. Finalize

```bash
.claude/skills/work/scripts/publish-beads.sh   # >=300s timeout
```

Never a bare `bd dolt push`: it has been observed exiting 0 while printing a
rejection, so the exit code alone is not a signal (`work`'s claim-sync.md;
computenet-kbk0). The script fails on either signal and recovers a
non-fast-forward inline.

Report: items closed (with PRs), items superseded-closed, items
rejected-closed (with the one-line why), items parked `needs-evidence`
(with what was asked for), items left open and why, and the one-command
oversight view for the human:

```bash
bd list --parent=computenet-wpvy --status=open --json
```
