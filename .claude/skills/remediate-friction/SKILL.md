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

`.claude/skills/remediate-friction/scripts/gate.sh` + `.claude/skills/remediate-friction/scripts/install-trigger.sh` make this lane
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
`~/Library/Logs/sdlc-orchestrator.log`, and `SDLC_DRY_RUN=1 .claude/skills/remediate-friction/scripts/gate.sh`
shows what the filter would do without launching anything.

This lane is deliberately separate from /work: a work session must never
edit the skill it is executing under, and this orchestrator must never look
like a work session to /work's concurrent-run check.

**The two lines that keep the lanes apart:**

- **Never claim the SDLC epic.** Address it by id and claim only its child
  items. Because this lane never puts an epic `in_progress`, /work's
  concurrent-run check (which lists `in_progress` epics) never sees it, and
  no work-session epic claim is consumed.
- **Touch only `.claude/skills/`.** All of it — `work/` and this lane's own
  files above all, but `sync-report/` and `work-unsupervised/` too. Product
  code is /work's lane, and that half is the real boundary. This used to name
  two directories, which left the other two skills owned by nobody: AGENTS.md
  forbids editing a skill outside this lane, so read literally they could not
  be edited at all (computenet-z9tu). The two mechanisms already assumed the
  wider scope — `validate-skills.rb` checks every skill, and `line-budget.txt`
  carries a budget for each — so the narrow line was the typo.

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

## 1b. Read the lane's own scoreboard first

```bash
.claude/skills/remediate-friction/scripts/recurrence-audit.py
```

Every `FAILED-FIX` line is one of this lane's own landed fixes that did **not**
stop the friction — a later bead names it and says so. **Take one of those
before any fresh single-instance report**: a recurrence is corroborated by
construction, being the first report plus the failure of the fix.

This lane ran for weeks with no feedback loop — it filed, fixed and closed, and
nothing asked whether an edit changed what agents do. The one measurement ever
taken from outside (computenet-olrv) found ~80% of fixes were uncorroborated
anecdotes; the remedy shipped for that was more instruction text, and the fix
rate then went **79% → 94%**. Read the scoreboard, not the intention. The
script's header carries the full measurement.

## 2. Pick the most-reported item

```bash
# NOT --status=open. Filing no longer claims, so most items are open — but
# the routing below has to SEE a claimed item to judge it: `theirs, skip` and
# `stale for 12h, steal it` are both unreachable from an open-only listing,
# and a claim abandoned mid-drain would be invisible forever. List everything
# non-closed and let the assignee routing decide.
#
# This listing was open-only until 2026-08-19, when file-friction.sh still
# pre-claimed at filing: it returned [] against a 48-item backlog and this
# lane reported the log drained (computenet-oxbv). Widening it was half the
# repair; the other half was dropping that filing claim, so `in_progress`
# here now means a session is draining the item rather than that one once
# reported it.
bd list --parent=computenet-wpvy --all --json \
  | sed -n '/^[[{]/,/^[]}]/p' \
  | jq '[ (if type=="array" then . else (.issues // []) end)[]
          | select(.status != "closed") ]'
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
hits the wall (`work` step 7). Then pick by assignee. **Unassigned is the
normal state** — filing does not claim (`file-friction.sh`'s header says
why), so a claim means a session started draining that item:

- **Unassigned** → an acquisition: claim by id, `bd dolt push`. Push
  rejected → pull, re-check the assignee, take the next item if it's gone.
- **Assigned to `$BEADS_ACTOR`** → yours already; work it, no sync needed.
- **Assigned to the other machine** → theirs; skip it — *unless* the claim
  is stale (untouched for more than 12h, per `updated_at` from the step-1
  pull; deliberately far above `work`'s 15-minute liveness window, because a
  single drain — triage, fix, gate, PR — can hold a claim for hours).
  Then steal it with the full bracket: `bd dolt pull` to confirm it is still
  untouched, re-claim, `bd dolt push`, and note the steal in a comment. This
  is the liveness backstop for a machine that claimed and then died
  mid-drain, and the reason this step lists claimed items at all.

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

  Then, before designing anything, **ask whether the fix can be MECHANICAL
  rather than prose** — a script, a changed default, normalised data, a flag
  that did not exist. The test is whether it works when nobody reads it:

  | `fix_kind` | takes effect |
  |---|---|
  | `mechanical` | whether or not any agent reads a word |
  | `prose` | only if read, at the moment it matters |

  **Every recorded recurrence in this log is a prose fix** (the audit in step
  1b lists them). Prose is sometimes the only honest option — a judgment call
  cannot be scripted — but reach for it second. The 2026-08-20 drain landed 5
  mechanical fixes to 21 prose, which is the ratio this question exists to
  shift.

  Stamp both and go to step 4:

  ```bash
  bd update <id> --set-metadata skill_version=<current-hash>
  bd update <id> --set-metadata fix_kind=<mechanical|prose>
  ```

  (Two calls, not one chained block — `bd` writes chained in a single Bash
  invocation die mid-sequence and leave half-recorded state, per
  `work/references/bd-traps.md`.) The field is what makes the scoreboard in
  step 1b able to compare the two classes; until enough fixes carry it the
  audit prints them as `?` and says so.
- **Needs evidence** — a single instance you cannot cheaply verify, or a
  judgment call ("confusing", "wasteful", "should have X"). Do **not** fix.
  Comment exactly what a future session must capture to make the case — the
  command, the verbatim output, what it cost — then park and release:

  ```bash
  bd update <id> --add-label needs-evidence --assignee="" --status=open
  ```

  All three flags matter: step 2's `--claim` set assignee *and*
  `in_progress`. Resetting `--status=open` is what makes the park visible as
  a park rather than as work in flight: step 2 lists everything non-closed,
  so an item left `in_progress` here would read as claimed-and-being-drained
  and never be re-examined.

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

It also enforces the **line-budget ratchet** (`.claude/skills/line-budget.txt`).
Budgets sit at each skill's current size, so nothing needs restructuring — what
it prices is growth. Over budget: remove as much as you added, or **add a delta
file** `.claude/skills/line-budget.d/<id>.txt` holding `<skill> <+N>` plus the
prose saying what it bought. Never edit the number in `line-budget.txt` — that
one shared line is why the lane used to ship one item per CI cycle
(computenet-kzyk); deltas are separate new files, so two PRs merge cleanly and
neither is recomputed. **A delta may be negative — write one whenever a drain
removes text.** (The old `ideal <=500` warning was computed and read past on
every run while `work/SKILL.md` went 668 → 2236 lines.)

**And check the fix is in a file the reporting role reads.** The bead names the
role that hit the wall; pass that role and the files you touched:

```bash
.claude/skills/remediate-friction/scripts/reachability.py --for <role> <edited-file>...
```

The role graph models `/work` and nothing else, so where it has no model the
script DECLINES rather than guessing: `--for` reports `NO-MODEL … check
placement by hand` for a file under another skill, and for any non-`.md` file
(a script takes effect by being RUN, so no markdown walk can reach it — that is
the whole point of a mechanical fix). Before computenet-z9tu those came back
`NOT-READ`, the script committing the very defect it exists to catch. `--for`
never answers `SERVED` outside `/work`: every modelled role is a `/work` role,
so that would assert something false. The bare form, which claims nothing about
a role, does credit a skill's own `SKILL.md` to its own readers.

**Editing a script? Run the sibling suites of everything the branch changed:**

```bash
.claude/skills/remediate-friction/scripts/sibling-tests.sh    # defaults to origin/main
```

It derives the set from the diff — name sibling (`foo.sh` -> `foo.test.sh`), else
any `*.test.*` in that `scripts/` dir naming the file OUTSIDE a comment (a
mention in prose is not coverage) — so it cannot go
stale, and it covers `work/scripts/` as well as this lane's own. Exit 1 means a
suite is RED; `NO-TEST` is reported and does not block. This replaces naming one
suite literally, which reached neither of the two instances that filed
computenet-hkjo: a script edit shipped with its suite unrun twice in one drain,
once leaving this lane's own discrimination suite red, both caught by a reviewer
rather than by the lane.

`NOT-READ` means the fix is correct and invisible — computenet-l5rc's glob-trap
remedy landed in a file the orchestrator never opens, so it recurred twice in a
day while a closed bead claimed it fixed. Advisory on placement, not a verdict
on the fix; it catches the zero case, which is the one that recurs.

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

On merge — which normally arrives while the NEXT item is already in flight,
so this is "when it merges, do this", not a barrier the loop below waits at:

```bash
bd close <id> --reason "fixed in <pr-url> against skill revision <hash>"
git worktree remove "$PWD/../computenet-worktrees/<id>"
```

Take the next item without waiting — repeat from step 2 while budget remains;
this lane is cheap per item, so several items per session is normal — **but
keep at most ~2 PRs open against any one file.** The bound and its reason live
in `work/references/direct-child.md` (computenet-nxac); restated here because
an agent in this lane has no reason to open that file. Budget growth is no
longer part of that bound: each PR writes its own `line-budget.d/<id>.txt`, so
budgets no longer serialise the lane (computenet-x69c cost four hand-resolved
rebases of the shared ledger in one minute; computenet-kzyk, five ready fixes
shipped one per CI cycle).

**Hold the next item rather than opening its PR; holding is not idleness.**
Verdicts that close an item (superseded, rejected, needs-evidence) need no PR,
and neither does verifying the next claim or drafting its diff in a worktree.
**A hold is only good within this session** — step 2's `--claim` set assignee
*and* `in_progress`, so a session that ends still holding strands the item
behind step 2's 12h stale-claim window. Either land it, or release it the way
step 3 releases a park: `bd update <id> --assignee="" --status=open`.

A script-only PR writes no delta — the ratchet prices only `SKILL.md` and
`references/*.md` — but the ~2-per-file bound still applies to two PRs editing
one script. Two changes still touch `line-budget.txt` itself: a change to
`validate-skills.rb`'s counting method, which moves every base number in the
same diff, and the fold-back — writing the accumulated deltas into the base
numbers and deleting the delta files, which only a session holding the ledger
alone should do.

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
bd list --parent=computenet-wpvy --all --json \
  | sed -n '/^[[{]/,/^[]}]/p' \
  | jq -r '[ (if type=="array" then . else (.issues // []) end)[]
             | select(.status != "closed") ]
           | sort_by(-.comment_count)[]
           | "\(.id)  c=\(.comment_count)  \(.assignee // "-")  \(.title)"'
```
